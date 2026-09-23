param(
    [string]$ResourceRoot = "",
    [string]$OutputDirectory = ""
)

# Raster previews are rendered from the same geometric paths as the Android
# VectorDrawables. The SVG and vectors remain the editable source of truth.
Add-Type -AssemblyName System.Drawing

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
if ([string]::IsNullOrWhiteSpace($ResourceRoot)) {
    $ResourceRoot = Join-Path $projectRoot "app\src\main\res"
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "rendered"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function Add-ScaledBezier {
    param(
        [System.Drawing.Drawing2D.GraphicsPath]$Path,
        [double]$Scale,
        [double[]]$Points
    )
    $Path.AddBezier(
        [single]($Points[0] * $Scale), [single]($Points[1] * $Scale),
        [single]($Points[2] * $Scale), [single]($Points[3] * $Scale),
        [single]($Points[4] * $Scale), [single]($Points[5] * $Scale),
        [single]($Points[6] * $Scale), [single]($Points[7] * $Scale)
    )
}

function New-RibbonPath {
    param([double]$Scale, [ValidateSet("cyan", "orange", "blue")][string]$Kind)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    switch ($Kind) {
        "cyan" {
            $path.StartFigure()
            Add-ScaledBezier $path $Scale @(34,34, 47,21, 68,22, 79,39)
            Add-ScaledBezier $path $Scale @(79,39, 87,51, 85,66, 76,76)
        }
        "orange" {
            $path.StartFigure()
            Add-ScaledBezier $path $Scale @(76,76, 67,85, 54,89, 42,85)
            Add-ScaledBezier $path $Scale @(42,85, 29,81, 22,71, 22,60)
            Add-ScaledBezier $path $Scale @(22,60, 22,49, 27,40, 34,34)
        }
        "blue" {
            $path.StartFigure()
            Add-ScaledBezier $path $Scale @(34,34, 47,27, 62,29, 71,40)
            Add-ScaledBezier $path $Scale @(71,40, 81,52, 78,69, 62,85)
        }
    }
    return $path
}

function New-Pen {
    param([System.Drawing.Color]$Color, [double]$Width, [double]$Scale)
    $pen = New-Object System.Drawing.Pen($Color, [single]($Width * $Scale))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    return $pen
}

function New-IconBitmap {
    param(
        [int]$Size,
        [System.Drawing.Color]$Background,
        [switch]$Monochrome
    )
    $bitmap = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.Clear($Background)
    $scale = $Size / 108.0
    $kinds = @("cyan", "orange", "blue")

    if (-not $Monochrome) {
        $underlay = New-Pen ([System.Drawing.Color]::FromArgb(255, 7, 18, 45)) 26 $scale
        foreach ($kind in $kinds) {
            $path = New-RibbonPath $scale $kind
            $graphics.DrawPath($underlay, $path)
            $path.Dispose()
        }
        $underlay.Dispose()

        $colors = @{
            cyan = [System.Drawing.Color]::FromArgb(255, 41, 224, 229)
            orange = [System.Drawing.Color]::FromArgb(255, 255, 156, 32)
            blue = [System.Drawing.Color]::FromArgb(255, 36, 107, 255)
        }
        foreach ($kind in $kinds) {
            $pen = New-Pen $colors[$kind] 17 $scale
            $path = New-RibbonPath $scale $kind
            $graphics.DrawPath($pen, $path)
            $pen.Dispose()
            $path.Dispose()
        }

        $highlight = New-Pen ([System.Drawing.Color]::FromArgb(142, 255, 255, 255)) 2.2 $scale
        $highlightPaths = @(
            @(38,31, 50,24, 65,26, 74,39),
            @(29,60, 29,51, 34,44, 41,40),
            @(73,45, 78,54, 75,66, 68,74)
        )
        foreach ($points in $highlightPaths) {
            $path = New-Object System.Drawing.Drawing2D.GraphicsPath
            $path.StartFigure()
            Add-ScaledBezier $path $scale $points
            $graphics.DrawPath($highlight, $path)
            $path.Dispose()
        }
        $highlight.Dispose()
    } else {
        $white = New-Pen ([System.Drawing.Color]::White) 18 $scale
        foreach ($kind in $kinds) {
            $path = New-RibbonPath $scale $kind
            $graphics.DrawPath($white, $path)
            $path.Dispose()
        }
        $white.Dispose()
    }
    $graphics.Dispose()
    return $bitmap
}

function Save-IconPng {
    param([int]$Size, [string]$Path, [System.Drawing.Color]$Background)
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $bitmap = New-IconBitmap $Size $Background
    $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

function Save-RoundPreview {
    param([int]$Size, [string]$Path, [System.Drawing.Color]$Background)
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $source = New-IconBitmap $Size $Background
    $preview = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($preview)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $mask = New-Object System.Drawing.Drawing2D.GraphicsPath
    $mask.AddEllipse(0, 0, $Size, $Size)
    $graphics.SetClip($mask)
    $graphics.DrawImage($source, 0, 0, $Size, $Size)
    $graphics.ResetClip()
    $graphics.Dispose()
    $mask.Dispose()
    $source.Dispose()
    $preview.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $preview.Dispose()
}

$dark = [System.Drawing.Color]::FromArgb(255, 11, 22, 51)
$light = [System.Drawing.Color]::FromArgb(255, 246, 247, 251)
$densities = @{
    mdpi = 48
    hdpi = 72
    xhdpi = 96
    xxhdpi = 144
    xxxhdpi = 192
}
foreach ($density in $densities.Keys) {
    $size = $densities[$density]
    Save-IconPng $size (Join-Path $ResourceRoot "mipmap-$density\ic_launcher.png") $dark
    Save-IconPng $size (Join-Path $ResourceRoot "mipmap-$density\ic_launcher_round.png") $dark
}
Save-IconPng 512 (Join-Path $OutputDirectory "remote-service-512.png") $dark
Save-IconPng 512 (Join-Path $OutputDirectory "remote-service-light-launcher.png") $light
Save-RoundPreview 512 (Join-Path $OutputDirectory "remote-service-dark-round-mask.png") $dark
Save-RoundPreview 512 (Join-Path $OutputDirectory "remote-service-light-round-mask.png") $light
$monochrome = New-IconBitmap 512 ([System.Drawing.Color]::Transparent) -Monochrome
$monochrome.Save((Join-Path $OutputDirectory "remote-service-monochrome-512.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$monochrome.Dispose()

Write-Output "Rendered 5 density pairs plus 512px dark/light/monochrome previews from the vector geometry."
