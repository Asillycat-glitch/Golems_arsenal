param(
    [Parameter(Mandatory = $true)]
    [string]$InputModel,

    [Parameter(Mandatory = $true)]
    [string]$OutputObj,

    [string]$MaterialTexture = "minecraft:block/netherite_block",

    [string]$OutputTexture = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Rotate-EulerXYZ {
    param(
        [double[]]$Point,
        [double[]]$Origin,
        [double[]]$Rotation
    )

    $xRadians = $Rotation[0] * [Math]::PI / 180.0
    $yRadians = $Rotation[1] * [Math]::PI / 180.0
    $zRadians = $Rotation[2] * [Math]::PI / 180.0
    $a = [Math]::Cos($xRadians)
    $b = [Math]::Sin($xRadians)
    $c = [Math]::Cos($yRadians)
    $d = [Math]::Sin($yRadians)
    $e = [Math]::Cos($zRadians)
    $f = [Math]::Sin($zRadians)
    $ae = $a * $e
    $af = $a * $f
    $be = $b * $e
    $bf = $b * $f

    # Blockbench mesh vertices are local to the element origin.
    $x = $Point[0]
    $y = $Point[1]
    $z = $Point[2]
    $rotatedX = $Origin[0] + $c * $e * $x - $c * $f * $y + $d * $z
    $rotatedY = $Origin[1] + ($af + $be * $d) * $x + ($ae - $bf * $d) * $y - $b * $c * $z
    $rotatedZ = $Origin[2] + ($bf - $ae * $d) * $x + ($be + $af * $d) * $y + $a * $c * $z

    return [double[]]@(
        $rotatedX,
        $rotatedY,
        $rotatedZ
    )
}

function Format-Number {
    param([double]$Value)

    return $Value.ToString("0.######", [Globalization.CultureInfo]::InvariantCulture)
}

$source = Get-Content -LiteralPath $InputModel -Raw | ConvertFrom-Json
if ($source.meta.model_format -ne "free") {
    throw "Expected a Blockbench free model, got '$($source.meta.model_format)'."
}

if ($OutputTexture) {
    $sourceTexture = @($source.textures) | Select-Object -First 1
    if ($null -eq $sourceTexture) {
        throw "The Blockbench model does not contain a texture."
    }

    $textureBytes = $null
    if ($sourceTexture.source -match '^data:image/png;base64,(.+)$') {
        $textureBytes = [Convert]::FromBase64String($Matches[1])
    } elseif ($sourceTexture.path -and (Test-Path -LiteralPath $sourceTexture.path)) {
        $textureBytes = [IO.File]::ReadAllBytes($sourceTexture.path)
    } else {
        throw "The Blockbench texture is neither embedded nor available on disk."
    }

    $textureOutputDirectory = Split-Path -Parent $OutputTexture
    [IO.Directory]::CreateDirectory($textureOutputDirectory) | Out-Null
    [IO.File]::WriteAllBytes($OutputTexture, $textureBytes)
}

$parts = [Collections.Generic.List[object]]::new()
$allPoints = [Collections.Generic.List[object]]::new()

foreach ($element in @($source.elements)) {
    if ($element.type -ne "mesh") {
        throw "Element '$($element.name)' is not a mesh."
    }

    $origin = [double[]]@($element.origin[0], $element.origin[1], $element.origin[2])
    $rotation = [double[]]@($element.rotation[0], $element.rotation[1], $element.rotation[2])

    $vertices = [ordered]@{}
    $localMinimum = [double[]]@([double]::PositiveInfinity, [double]::PositiveInfinity, [double]::PositiveInfinity)
    $localMaximum = [double[]]@([double]::NegativeInfinity, [double]::NegativeInfinity, [double]::NegativeInfinity)
    foreach ($property in @($element.vertices.psobject.Properties)) {
        $raw = $property.Value
        $point = [double[]]@($raw[0], $raw[1], $raw[2])
        for ($axis = 0; $axis -lt 3; $axis++) {
            $localMinimum[$axis] = [Math]::Min($localMinimum[$axis], $point[$axis])
            $localMaximum[$axis] = [Math]::Max($localMaximum[$axis], $point[$axis])
        }
        $worldPoint = Rotate-EulerXYZ -Point $point -Origin $origin -Rotation $rotation
        $vertices[$property.Name] = $worldPoint
        $allPoints.Add($worldPoint)
    }

    $doubleSided = $false
    for ($axis = 0; $axis -lt 3; $axis++) {
        if ([Math]::Abs($localMaximum[$axis] - $localMinimum[$axis]) -le 0.000001) {
            $doubleSided = $true
        }
    }

    $parts.Add([pscustomobject]@{
        Name = $element.name
        Vertices = $vertices
        Faces = $element.faces
        DoubleSided = $doubleSided
    })
}

$minimum = [double[]]@([double]::PositiveInfinity, [double]::PositiveInfinity, [double]::PositiveInfinity)
$maximum = [double[]]@([double]::NegativeInfinity, [double]::NegativeInfinity, [double]::NegativeInfinity)
foreach ($point in $allPoints) {
    for ($axis = 0; $axis -lt 3; $axis++) {
        $minimum[$axis] = [Math]::Min($minimum[$axis], $point[$axis])
        $maximum[$axis] = [Math]::Max($maximum[$axis], $point[$axis])
    }
}

$centerX = ($minimum[0] + $maximum[0]) / 2.0
$centerY = ($minimum[1] + $maximum[1]) / 2.0
$centerZ = ($minimum[2] + $maximum[2]) / 2.0
$center = [double[]]@($centerX, $centerY, $centerZ)
$largestSpan = 0.0
for ($axis = 0; $axis -lt 3; $axis++) {
    $largestSpan = [Math]::Max($largestSpan, $maximum[$axis] - $minimum[$axis])
}
if ($largestSpan -le 0.0) {
    throw "The model has no measurable size."
}

# Forge OBJ coordinates use one block as one unit. Keep a small border around the item.
$scale = 0.9 / $largestSpan
$textureWidth = [double]$source.resolution.width
$textureHeight = [double]$source.resolution.height
if (@($source.textures).Count -gt 0) {
    if ($source.textures[0].uv_width) {
        $textureWidth = [double]$source.textures[0].uv_width
    }
    if ($source.textures[0].uv_height) {
        $textureHeight = [double]$source.textures[0].uv_height
    }
}
$objLines = [Collections.Generic.List[string]]::new()
$mtlFileName = [IO.Path]::GetFileNameWithoutExtension($OutputObj) + ".mtl"
$objLines.Add("mtllib $mtlFileName")
$objLines.Add("o golem_energy_mechanical_bow")
$objLines.Add("s off")

$vertexIndex = 1
$textureIndex = 1
$partIndex = 0
foreach ($part in $parts) {
    $partIndex++
    $objLines.Add("g bow_part_$($partIndex.ToString('00'))")
    $objLines.Add("usemtl bow_material")

    $indices = [ordered]@{}
    foreach ($entry in $part.Vertices.GetEnumerator()) {
        $point = $entry.Value
        $x = ($point[0] - $center[0]) * $scale + 0.5
        $y = ($point[1] - $center[1]) * $scale + 0.5
        $z = ($point[2] - $center[2]) * $scale + 0.5
        $objLines.Add("v $(Format-Number $x) $(Format-Number $y) $(Format-Number $z)")
        $indices[$entry.Key] = $vertexIndex
        $vertexIndex++
    }

    foreach ($faceProperty in @($part.Faces.psobject.Properties)) {
        $face = $faceProperty.Value
        $faceTokens = [Collections.Generic.List[string]]::new()
        foreach ($vertexName in @($face.vertices)) {
            $uvProperty = $face.uv.psobject.Properties | Where-Object Name -EQ $vertexName | Select-Object -First 1
            if ($null -eq $uvProperty) {
                throw "Face '$($faceProperty.Name)' has no UV for vertex '$vertexName'."
            }

            $uv = $uvProperty.Value
            $u = [double]$uv[0] / $textureWidth
            $v = [double]$uv[1] / $textureHeight
            $objLines.Add("vt $(Format-Number $u) $(Format-Number $v)")
            $faceTokens.Add("$($indices[$vertexName])/$textureIndex")
            $textureIndex++
        }
        $objLines.Add("f $($faceTokens -join ' ')")
        if ($part.DoubleSided) {
            $reversedTokens = @($faceTokens)
            [Array]::Reverse($reversedTokens)
            $objLines.Add("f $($reversedTokens -join ' ')")
        }
    }
}

$outputDirectory = Split-Path -Parent $OutputObj
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllLines($OutputObj, $objLines, $utf8)

$outputMtl = [IO.Path]::ChangeExtension($OutputObj, ".mtl")
$mtlLines = [string[]]@(
    "newmtl bow_material",
    "Ka 0 0 0",
    "Kd 1 1 1 1",
    "d 1",
    "map_Kd $MaterialTexture"
)
[IO.File]::WriteAllLines($outputMtl, $mtlLines, $utf8)

Write-Output "Converted $($parts.Count) mesh elements."
Write-Output "Source bounds: $($minimum -join ', ') to $($maximum -join ', ')"
Write-Output "OBJ scale: $(Format-Number $scale)"
if ($OutputTexture) {
    Write-Output "Extracted texture to $OutputTexture"
}
