param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ $_.StartsWith('https://', [StringComparison]::OrdinalIgnoreCase) })]
    [string]$WebhookUrl
)

$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 defaults to older TLS versions; Telegram requires 1.2+.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function ConvertFrom-SecureInput([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

$botTokenInput = Read-Host 'Bot token (hidden; not saved)' -AsSecureString
$botToken = $null
$webhookSecret = $null
$rng = $null
$secretBytes = $null
$stage = 'validazione input'

try {
    $botToken = ConvertFrom-SecureInput $botTokenInput

    if ([string]::IsNullOrWhiteSpace($botToken) -or $botToken.Length -gt 256) {
        throw 'Invalid bot token.'
    }

    # Generate a fresh webhook secret locally and upload it without storing it
    # in the repository or showing it in the console. This guarantees the
    # Worker and Telegram receive the same value without asking the user to
    # retrieve an existing Cloudflare secret.
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $secretBytes = New-Object byte[] 32
    $rng.GetBytes($secretBytes)
    $webhookSecret = [Convert]::ToBase64String($secretBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

    $stage = 'upload del segreto a Cloudflare'
    Push-Location $PSScriptRoot
    try {
        $webhookSecret | npx --yes wrangler@latest secret put TELEGRAM_WEBHOOK_SECRET | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not upload the webhook secret to Cloudflare.' }
    }
    finally {
        Pop-Location
    }

    $payload = @{
        url = $WebhookUrl.TrimEnd('/')
        secret_token = $webhookSecret
        allowed_updates = @('channel_post', 'message')
    } | ConvertTo-Json -Depth 4 -Compress

    $stage = 'registrazione webhook su Telegram'
    $telegramUrl = 'https://api.telegram.org/bot{0}/setWebhook' -f $botToken
    $result = Invoke-RestMethod -Uri $telegramUrl -Method Post -ContentType 'application/json' -Body $payload
    if (-not $result.ok) {
        throw 'Telegram rejected the webhook configuration.'
    }
    Write-Output 'Webhook configurato. Il token bot non è stato scritto su file.'
}
catch {
    Write-Host "Webhook non configurato: errore durante $stage." -ForegroundColor Red
    if ($stage -eq 'registrazione webhook su Telegram') {
        Write-Host 'Verifica che il token inserito sia quello attivo appena generato da BotFather. Il token non è stato mostrato.'
    }
    exit 1
}
finally {
    $botToken = $null
    $webhookSecret = $null
    $botTokenInput = $null
    if ($secretBytes) { [Array]::Clear($secretBytes, 0, $secretBytes.Length) }
    if ($rng) { $rng.Dispose() }
    $payload = $null
    $telegramUrl = $null
    $result = $null
}
