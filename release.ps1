[CmdletBinding()]
param (
    [Parameter(HelpMessage = "Push tag to remote origin.")]
    [switch]$Commit
)

$ErrorActionPreference = 'Stop'
$InformationPreference = 'Continue'

# Fetch SHA of last successful run for android.yml
$sha = gh run list --workflow android.yml --status success --limit 1 --json headSha --jq ".[0].headSha"

if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($sha)) {
    throw "Unable to retrieve a successful workflow run SHA for android.yml."
}

# Fetch last tagged version
$lastTag = git describe --tags --abbrev=0 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($lastTag)) {
    $lastTag = "None"
}

Write-Information "Last successful build SHA: $sha"
Write-Information "Last tagged version:       $lastTag"

$newTag = Read-Host "Enter the new version tag to increment"

if ([string]::IsNullOrWhiteSpace($newTag)) {
    throw "No version tag provided."
}

if ($Commit) {
    Write-Information "Tagging commit $sha as $newTag..."
    git tag $newTag $sha
    if ($LASTEXITCODE -ne 0) { throw "git tag command failed." }

    Write-Information "Pushing tag $newTag to origin..."
    git push origin $newTag
    if ($LASTEXITCODE -ne 0) { throw "git push command failed." }
} else {
    Write-Information "`n[DRY RUN]"
    Write-Information "Target Commit : $sha"
    Write-Information "New Tag       : $newTag"
    Write-Information "Target Remote : origin"
    Write-Information "Execute with '-Commit' flag to apply changes."
}