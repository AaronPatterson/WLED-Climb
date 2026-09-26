<#
.SYNOPSIS
    Cuts a release: bumps the version, builds a signed APK, verifies the
    signature, and publishes it as a GitHub release.

.DESCRIPTION
    The steps are individually easy and collectively easy to get wrong three
    months from now. In particular, forgetting to bump versionCode produces an
    update that silently does not apply on any device, with nothing to show for
    it but a confused six-year-old.

    Every check here fails loudly rather than carrying on. A release that stops
    halfway costs a minute; one that ships a bad artifact costs a round trip to
    every device in the house.

.PARAMETER VersionName
    The human-facing version, e.g. "0.4.0", or "0.10.0-beta.1" with -Prerelease.
    versionCode is bumped automatically.

.PARAMETER Prerelease
    Cuts a beta from the current branch instead of a release from main.

    Everything about the artifact is the same: same signing key, same
    applicationId, so it installs over whatever is already there and is a real
    build rather than a preview of one. What changes is where it may come from
    and who is offered it - the GitHub release is marked as a prerelease, which
    Obtainium skips unless an app is explicitly set to include them.

    It does not push to main. The tag points at the branch commit, which is the
    honest thing for a build made from work that has not landed.

.PARAMETER DryRun
    Do everything except commit, tag, push and publish. Use this to check the
    build and signature without creating anything permanent.

.EXAMPLE
    ./scripts/release.ps1 -VersionName 0.4.0 -DryRun
    ./scripts/release.ps1 -VersionName 0.4.0
    ./scripts/release.ps1 -VersionName 0.10.0-beta.1 -Prerelease
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$')][string]$VersionName,
    [switch]$Prerelease,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Step($message) { Write-Host "`n==> $message" -ForegroundColor Cyan }
function Fail($message) { Write-Host "`nFAILED: $message" -ForegroundColor Red; exit 1 }

$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

$gradleFile = Join-Path $repoRoot 'app/build.gradle.kts'
$apkPath    = Join-Path $repoRoot 'app/build/outputs/apk/release/app-release.apk'
$aabPath    = Join-Path $repoRoot 'app/build/outputs/bundle/release/app-release.aab'
$tag        = "v$VersionName"

# --- Preconditions -----------------------------------------------------------
# Checked before anything is modified, so a failure here leaves no mess.

Step 'Checking the working tree'

if (git status --porcelain) {
    Fail "Working tree is dirty. Commit or stash first - this script makes a version-bump commit and won't sweep unrelated changes into it."
}

$branch = git rev-parse --abbrev-ref HEAD
if (-not $Prerelease -and $branch -ne 'main') {
    Fail "On branch '$branch'. Releases are cut from main, otherwise the tag points at history that isn't published. For a build from this branch use -Prerelease."
}

if ($VersionName -match '-' -and -not $Prerelease) {
    Fail "'$VersionName' is a prerelease version. Pass -Prerelease, or give a plain x.y.z version."
}
if ($Prerelease -and $VersionName -notmatch '-') {
    Fail "'$VersionName' is not a prerelease version. A beta wants a suffix like 0.10.0-beta.1, so nobody has to guess which build they are on."
}

if (git tag --list $tag) {
    Fail "Tag $tag already exists. Pick a new version - a tag that moves is worse than one that's missing."
}

git fetch --quiet origin
$upstream = if ($Prerelease) { "origin/$branch" } else { 'origin/main' }
if (-not (git rev-parse --verify --quiet $upstream)) {
    Fail "$upstream does not exist. Push the branch first, so the tag points at history someone else can fetch."
}
if ((git rev-parse HEAD) -ne (git rev-parse $upstream)) {
    Fail "Local $branch differs from $upstream. Pull or push first, so the tag matches what people can actually download."
}

if (-not $env:JAVA_HOME) {
    Fail "JAVA_HOME is not set. See docs/releasing.md."
}

# Resolved up front rather than at the publish step. Discovering it is missing
# after the tag has been pushed leaves a tag with no release behind it.
$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { $gh = 'C:\Program Files\GitHub CLI\gh.exe' }
if (-not (Test-Path $gh)) {
    Fail "GitHub CLI not found on PATH or at $gh. Install it, or the release can be built but not published."
}

# --- Version bump ------------------------------------------------------------

Step 'Bumping the version'

$content = Get-Content $gradleFile -Raw
if ($content -notmatch 'versionCode\s*=\s*(\d+)') { Fail "Couldn't find versionCode in $gradleFile" }
$currentCode = [int]$Matches[1]
$newCode = $currentCode + 1

if ($content -notmatch 'versionName\s*=\s*"([^"]+)"') { Fail "Couldn't find versionName in $gradleFile" }
$currentName = $Matches[1]

Write-Host "  versionCode $currentCode -> $newCode"
Write-Host "  versionName $currentName -> $VersionName"

$content = $content -replace 'versionCode\s*=\s*\d+', "versionCode = $newCode"
$content = $content -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$VersionName`""
Set-Content -Path $gradleFile -Value $content -NoNewline

# --- Build -------------------------------------------------------------------

Step 'Building the signed release'

# Both artifacts from one invocation, so they cannot disagree about what they
# contain: the APK goes to GitHub releases for Obtainium, the AAB goes to Play,
# and building them separately would eventually ship a mismatched pair.
#
# Deliberately not passing -PallowUnsigned: if credentials are missing this
# should stop here, not produce something no device can install.
& "$repoRoot/gradlew.bat" assembleRelease bundleRelease
if ($LASTEXITCODE -ne 0) {
    git checkout -- $gradleFile
    Fail 'Build failed. The version bump has been reverted.'
}

if (-not (Test-Path $apkPath)) {
    git checkout -- $gradleFile
    Fail "Expected $apkPath. An app-release-unsigned.apk here means signing was skipped."
}

if (-not (Test-Path $aabPath)) {
    git checkout -- $gradleFile
    Fail "Expected $aabPath. Play will not accept an APK, so a release without this is only half done."
}

# --- Verify the signature ----------------------------------------------------
# The build already refuses to produce an unsigned APK, so this is belt and
# braces. It costs a second, and the failure it guards against is only
# discoverable on the devices themselves.

Step 'Verifying the signature'

$apksigner = Get-ChildItem "$env:LOCALAPPDATA/Android/Sdk/build-tools/*/apksigner.bat" |
    Sort-Object { [version]($_.Directory.Name -replace '-.*$') } |
    Select-Object -Last 1
if (-not $apksigner) { Fail 'apksigner not found in the Android SDK build-tools.' }

$verifyOutput = & $apksigner.FullName verify --print-certs -v $apkPath 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { Fail "apksigner rejected the APK:`n$verifyOutput" }

foreach ($scheme in 'v2', 'v3') {
    if ($verifyOutput -notmatch "Scheme $scheme\): true") {
        Fail "APK is not signed with the $scheme scheme:`n$verifyOutput"
    }
}
Write-Host '  v2 and v3 present'

if ($verifyOutput -match 'certificate DN: ([^\r\n]+)') { Write-Host "  $($Matches[1])" }

# --- Publish -----------------------------------------------------------------

$asset = Join-Path $repoRoot "app/build/outputs/apk/release/wled-climb-$VersionName.apk"
Copy-Item $apkPath $asset -Force

if ($DryRun) {
    Step 'Dry run - stopping before anything permanent'
    $kind = if ($Prerelease) { 'Prerelease' } else { 'Release' }
    Write-Host "  Would commit:  $kind $VersionName"
    if ($Prerelease) { Write-Host "  Would push to: $branch (not main)" }
    Write-Host "  Would tag:     $tag"
    Write-Host "  Would publish: $(Split-Path $asset -Leaf)"
    Write-Host "  Play bundle:   $aabPath"
    Write-Host "`n  Reverting the version bump."
    git checkout -- $gradleFile
    Remove-Item $asset -Force
    exit 0
}

Step 'Committing, tagging and publishing'

git add $gradleFile
$commitSubject = if ($Prerelease) { "Prerelease $VersionName" } else { "Release $VersionName" }
git commit --quiet -m "$commitSubject

versionCode $currentCode -> $newCode, so devices see this as an update.
Built and signature-verified by scripts/release.ps1."

git tag -a $tag -m "$commitSubject"

# main requires a pull request, and this pushes to it directly. That is
# deliberate and it is the only sanctioned exception: branch protection leaves
# administrators unenforced precisely so this keeps working, because a release
# that fails here has already bumped, built, verified and committed, and would
# strand a local commit and a tag with nowhere to go.
#
# If that exemption is ever removed, do not paper over it by force-pushing.
# Move the version bump into its own pull request and leave this script to tag,
# build and publish only - then it never needs to write to main at all.
# For a prerelease this is the feature branch, which needs no exemption.
git push --quiet origin $branch
git push --quiet origin $tag

if ($Prerelease) {
    & $gh release create $tag $asset --prerelease `
        --title "$VersionName" `
        --notes "Beta build from branch `$branch`, signed with the release key and carrying the same applicationId as a release - so it installs over whatever is already on the device.

Marked as a prerelease. Obtainium skips these unless an app is set to include prereleases, so devices tracking stable releases are not offered it.

First install on a device that currently has a debug build needs an uninstall first - the signing certificate differs. See docs/releasing.md."
} else {
    & $gh release create $tag $asset `
        --title "$VersionName" `
        --notes "Signed release build. Install via Obtainium, or download the APK directly.

First install on a device that currently has a debug build needs an uninstall first - the signing certificate differs. See docs/releasing.md."
}

Step "$(if ($Prerelease) { 'Prereleased' } else { 'Released' }) $VersionName"
Write-Host "  https://github.com/AaronPatterson/WLED-Climbing-Wall/releases/tag/$tag"

# The bundle is deliberately not attached to the GitHub release. Nothing can
# install an AAB directly - it is an upload format that Play turns into
# per-device APKs - so publishing it beside the APK would only invite someone
# to download the wrong file.
Step 'Play bundle ready to upload'
Write-Host "  $aabPath"
Write-Host '  Play Console -> Testing -> Internal testing -> Create new release'
