$ErrorActionPreference = "Stop"

# Run from the root of your existing Spring Boot project.
if (-not (Test-Path "pom.xml")) {
    throw "Run this script from your project folder containing pom.xml."
}

$destination = Join-Path (Get-Location) "src/main/resources/static/vendor/pdfjs"
if (Test-Path $destination) {
    throw "PDF.js destination already exists. Keep it or move it aside before installing another version."
}

$temporary = Join-Path ([IO.Path]::GetTempPath()) ("pdfjs-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/mozilla/pdf.js/releases/tags/v5.4.624" -Headers @{"User-Agent"="Local-PDF-Highlighter"}

    $asset = $release.assets |
    Where-Object { $_.name -eq "pdfjs-5.4.624-legacy-dist.zip" } |
    Select-Object -First 1
    if (-not $asset) { throw "No standard PDF.js distribution ZIP was found in the latest release." }
    $archive = Join-Path $temporary "pdfjs.zip"
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $archive -UseBasicParsing
    $unpacked = Join-Path $temporary "unpacked"
    Expand-Archive -LiteralPath $archive -DestinationPath $unpacked
    if (-not (Test-Path (Join-Path $unpacked "build/pdf.mjs"))) {
        throw "Unexpected PDF.js archive layout. Use the manual installation instructions."
    }
    New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
    Move-Item -LiteralPath $unpacked -Destination $destination
    Write-Host "Installed PDF.js $($release.tag_name). Restart your Spring Boot app."
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force
}
