param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$metadataUrl = "https://fill.papermc.io/v3/projects/paper/versions/$Version/builds/latest"
$metadata = Invoke-RestMethod -Uri $metadataUrl
$downloadUrl = $metadata.downloads.'server:default'.url

if ([string]::IsNullOrWhiteSpace($downloadUrl)) {
    throw "Paper $Version has no downloadable build."
}

Write-Host "Downloading $downloadUrl"
Invoke-WebRequest -Uri $downloadUrl -OutFile $OutputPath
Write-Host "Saved Paper server to $OutputPath"
