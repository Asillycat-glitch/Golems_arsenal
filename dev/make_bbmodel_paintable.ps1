param(
    [Parameter(Mandatory = $true)]
    [string]$InputModel,

    [Parameter(Mandatory = $true)]
    [string]$OutputModel,

    [Parameter(Mandatory = $true)]
    [string]$OutputTexture
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$model = Get-Content -LiteralPath $InputModel -Raw | ConvertFrom-Json
if ($model.meta.model_format -ne "free") {
    throw "Expected a Blockbench free model, got '$($model.meta.model_format)'."
}

$width = [int]$model.resolution.width
$height = [int]$model.resolution.height
if ($width -le 0 -or $height -le 0) {
    throw "The model texture resolution is invalid."
}

Add-Type -AssemblyName System.Drawing
$bitmap = [Drawing.Bitmap]::new($width, $height, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$graphics.Clear([Drawing.Color]::FromArgb(255, 128, 128, 128))

$textureDirectory = Split-Path -Parent $OutputTexture
[IO.Directory]::CreateDirectory($textureDirectory) | Out-Null
$bitmap.Save($OutputTexture, [Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()

$textureBytes = [IO.File]::ReadAllBytes($OutputTexture)
$textureSource = "data:image/png;base64,$([Convert]::ToBase64String($textureBytes))"
$texture = [ordered]@{
    name = [IO.Path]::GetFileName($OutputTexture)
    path = ""
    folder = ""
    namespace = ""
    id = "0"
    group = ""
    scope = 0
    width = $width
    height = $height
    uv_width = $width
    uv_height = $height
    particle = $false
    use_as_default = $true
    layers_enabled = $false
    sync_to_project = ""
    file_format = "png"
    render_mode = "default"
    render_sides = "auto"
    wrap_mode = "limited"
    pbr_channel = "color"
    fps = 7
    frame_time = 1
    frame_order_type = "loop"
    frame_order = ""
    frame_interpolate = $false
    visible = $true
    internal = $true
    saved = $false
    uuid = [Guid]::NewGuid().ToString()
    source = $textureSource
}

$model.textures = @($texture)
$boundFaces = 0
foreach ($element in @($model.elements)) {
    foreach ($faceProperty in @($element.faces.psobject.Properties)) {
        $faceProperty.Value | Add-Member -NotePropertyName texture -NotePropertyValue 0 -Force
        $boundFaces++
    }
}

$model.name = [IO.Path]::GetFileNameWithoutExtension($OutputModel)
$modelDirectory = Split-Path -Parent $OutputModel
[IO.Directory]::CreateDirectory($modelDirectory) | Out-Null
$json = $model | ConvertTo-Json -Depth 100 -Compress
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($OutputModel, $json, $utf8)

Write-Output "Added one embedded ${width}x${height} texture and bound $boundFaces faces."
