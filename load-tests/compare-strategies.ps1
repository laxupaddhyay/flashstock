param(
    [ValidateSet("conditional", "row-lock")][string]$Strategy = "conditional",
    [string]$User = "demo",
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Rate = 100,
    [string]$Duration = "2m",
    [int]$Stock = 100,
    [int]$Quantity = 1
)

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $env:INVENTORY_RESERVATION_STRATEGY = $Strategy
    docker compose up -d --build --force-recreate app
    if ($LASTEXITCODE -ne 0) { throw "Failed to start FlashStock with strategy $Strategy." }

    $deadline = (Get-Date).AddMinutes(2)
    do {
        try {
            Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 3 | Out-Null
            break
        } catch {
            if ((Get-Date) -ge $deadline) { throw "FlashStock did not become ready within two minutes." }
            Start-Sleep -Seconds 2
        }
    } while ($true)

    $pair = "${User}:${Password}"
    $headers = @{ Authorization = "Basic $([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair)))" }
    $productBody = @{
        name = "FlashStock benchmark $Strategy"
        sku = "BENCH-$Strategy-$([Guid]::NewGuid().ToString('N'))"
        price = "1.00"
        initialQuantity = $Stock
    } | ConvertTo-Json
    $product = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/products" -Headers $headers -ContentType "application/json" -Body $productBody

    $env:BASE_URL = $BaseUrl
    $env:PRODUCT_ID = $product.id
    $env:DEV_USER = $User
    $env:DEV_PASSWORD = $Password
    $env:REQUEST_RATE = "$Rate"
    $env:TEST_DURATION = $Duration
    $env:REQUEST_QUANTITY = "$Quantity"
    k6 run (Join-Path $PSScriptRoot "flash-sale.js")
    if ($LASTEXITCODE -ne 0) { throw "k6 failed for strategy $Strategy." }
    Write-Host "Summary written to load-tests/summary-$Strategy.json"
} finally {
    Remove-Item Env:INVENTORY_RESERVATION_STRATEGY -ErrorAction SilentlyContinue
    Remove-Item Env:BASE_URL, Env:PRODUCT_ID, Env:DEV_USER, Env:DEV_PASSWORD, Env:REQUEST_RATE, Env:TEST_DURATION, Env:REQUEST_QUANTITY -ErrorAction SilentlyContinue
    Pop-Location
}
