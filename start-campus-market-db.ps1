[CmdletBinding()]
param(
    [string]$ProductImagesDir,
    [string]$BackendJar = 'backend\target\campus-market-stage61.jar'
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$schema = if ($env:DB_SCHEMA) { $env:DB_SCHEMA } else { '2024011324' }
if ($schema -notmatch '^[A-Za-z0-9_]{1,64}$') { throw 'DB_SCHEMA must be a simple MySQL schema name.' }
$jdbcUrl = if ($env:DB_URL) { $env:DB_URL } else { "jdbc:mysql://127.0.0.1:3306/$schema" }
if ($jdbcUrl -cnotmatch '^jdbc:mysql://[^/?#]+/(?<schema>[A-Za-z0-9_]{1,64})(?:\?[^#]*)?$' -or
    $Matches['schema'] -cne $schema) {
    throw 'DB_URL must be a MySQL JDBC URL whose database matches DB_SCHEMA. For rollback, set both values to campus_market_dev or clear DB_URL to derive it from DB_SCHEMA.'
}

if ([string]::IsNullOrWhiteSpace($ProductImagesDir)) {
    $ProductImagesDir = $env:PRODUCT_IMAGES_DIR
}
if ([string]::IsNullOrWhiteSpace($ProductImagesDir)) {
    $ProductImagesDir = Join-Path (Split-Path -LiteralPath $projectRoot) 'CampusMarket-data\product-images'
} elseif (-not [IO.Path]::IsPathRooted($ProductImagesDir)) {
    $ProductImagesDir = Join-Path $projectRoot $ProductImagesDir
}

$jar = if ([IO.Path]::IsPathRooted($BackendJar)) { $BackendJar } else { Join-Path $projectRoot $BackendJar }
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'The selected backend JAR is missing.' }

$images = (Resolve-Path -LiteralPath $ProductImagesDir).ProviderPath
if (-not (Test-Path -LiteralPath $images -PathType Container)) { throw 'The product image path must be a directory.' }
$privatePath = $env:SELLER_PRIVATE_DOCUMENTS_DIR
if ([string]::IsNullOrWhiteSpace($privatePath)) {
    $defaultPrivatePath = Join-Path $env:LOCALAPPDATA 'CampusMarket\private-seller-documents'
    if (Test-Path -LiteralPath $defaultPrivatePath -PathType Container) { $privatePath = $defaultPrivatePath }
}

function Test-BelowOrEqual([string]$Path, [string]$Root) {
    $comparison = [StringComparison]::OrdinalIgnoreCase
    return $Path.Equals($Root, $comparison) -or $Path.StartsWith($Root.TrimEnd('\') + '\', $comparison)
}

if (Test-BelowOrEqual $images $projectRoot) { throw 'The product image directory must be outside the project.' }
if ($privatePath) {
    $privatePath = (Resolve-Path -LiteralPath $privatePath).ProviderPath
    if (-not (Test-Path -LiteralPath $privatePath -PathType Container) -or
        (Test-BelowOrEqual $privatePath $projectRoot) -or
        (Test-BelowOrEqual $images $privatePath) -or (Test-BelowOrEqual $privatePath $images)) {
        throw 'Use separate directories outside the project for product images and seller documents.'
    }
}

$original = @{}
foreach ($name in 'DB_SCHEMA', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'BANK_ACCOUNT_ENCRYPTION_KEY', 'PRODUCT_IMAGES_DIR', 'SELLER_PRIVATE_DOCUMENTS_DIR') {
    $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Read-LocalSecret([string]$Prompt) {
    $secret = Read-Host $Prompt -AsSecureString
    try { return [System.Net.NetworkCredential]::new('', $secret).Password }
    finally { $secret.Dispose() }
}

Push-Location -LiteralPath $projectRoot
try {
    $env:DB_SCHEMA = $schema
    $env:DB_URL = $jdbcUrl
    if (-not $env:DB_USERNAME) { $env:DB_USERNAME = Read-Host 'MySQL development username' }
    if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = Read-LocalSecret 'MySQL password' }
    if (-not $env:BANK_ACCOUNT_ENCRYPTION_KEY) {
        $env:BANK_ACCOUNT_ENCRYPTION_KEY = Read-LocalSecret 'Existing bank-account encryption key (Base64)'
    }
    $env:PRODUCT_IMAGES_DIR = $images
    $env:SELLER_PRIVATE_DOCUMENTS_DIR = $privatePath
    Write-Host "Expected database schema: $schema"
    Write-Host "Product image directory: $images"
    & java -jar $jar '--spring.profiles.active=db'
    if ($LASTEXITCODE -ne 0) { throw "The backend exited with code $LASTEXITCODE." }
} finally {
    foreach ($name in $original.Keys) { [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process') }
    Pop-Location
}
