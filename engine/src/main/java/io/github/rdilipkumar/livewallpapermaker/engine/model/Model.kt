package io.github.rdilipkumar.livewallpapermaker.engine.model

import kotlinx.serialization.Serializable

/**
 * A wallpaper project: one or more scenes made of layers. Saved as project.json.
 * Everything has a default so older files keep loading after new fields are added.
 */
@Serializable
data class Project(
  val id: String,
  val name: String = "Untitled",
  val formatVersion: Int = 1,
  val assets: List<Asset> = emptyList(),
  val scenes: List<Scene> = listOf(Scene(id = "scene-1")),
  val switching: SceneSwitching = SceneSwitching(),
  val settings: PlaybackSettings = PlaybackSettings(),
  val createdAt: Long = 0,
  val updatedAt: Long = 0,
) {
  fun asset(id: String?): Asset? = if (id == null) null else assets.firstOrNull { it.id == id }
  fun scene(id: String): Scene? = scenes.firstOrNull { it.id == id }
}

/** A prepared media file in the shared asset folder (videos are already loop-ready MP4s). */
@Serializable
data class Asset(
  val id: String,
  val kind: AssetKind,
  val file: String,
  val width: Int,
  val height: Int,
  val durationMs: Long = 0,
  val hasAlpha: Boolean = false,
  val label: String = "",
  /** How the video was prepared, so it can be prepared again with other settings. */
  val prep: VideoPrep? = null,
  /** For STICKER assets: how the frames are laid out in the sheet image. */
  val sticker: StickerInfo? = null,
)

/** VIDEO = loop-ready MP4, IMAGE = photo/PNG, STICKER = transparent animation sheet (PNG), FONT = TTF/OTF for text. */
@Serializable
enum class AssetKind { VIDEO, IMAGE, STICKER, FONT }

/** An animated sticker stored as one PNG sheet: frames left to right, top to bottom, with [pad] px between cells. */
@Serializable
data class StickerInfo(
  val cols: Int,
  val rows: Int,
  val frameW: Int,
  val frameH: Int,
  val delays: List<Int>,
  val pad: Int = 2,
) {
  val totalMs: Int get() = delays.sum().coerceAtLeast(1)
}

@Serializable
data class VideoPrep(
  val sourceFile: String? = null,
  val trimStartMs: Long = 0,
  val trimEndMs: Long = 0,
  val loop: LoopStyle = LoopStyle.CROSSFADE,
  val loopWindowMs: Int = 500,
  val fromGif: Boolean = false,
  val smoothGif: Boolean = false,
)

/** How the end of a clip joins its start. Baked into the MP4 when it is prepared. */
@Serializable
enum class LoopStyle { NONE, CROSSFADE, ZOOM_DISSOLVE, FADE_BLACK, PING_PONG }

@Serializable
data class Scene(
  val id: String,
  val name: String = "Scene",
  val background: Int = 0xFF000000.toInt(),
  val layers: List<Layer> = emptyList(),
  /** Transition used when this scene comes on screen. */
  val transitionIn: Transition = Transition(),
  /** Only show this scene during these hours (null = any time). */
  val timeWindow: TimeWindow? = null,
  val onlyWhileCharging: Boolean = false,
  /** Plays once after unlocking, then the wallpaper goes back to the normal scenes. */
  val isIntro: Boolean = false,
  /** How long an intro plays; 0 = one loop of its longest video (or 3 s). */
  val introDurationSec: Float = 0f,
  /** Lock screen only, home screen only, or both. */
  val showOn: ScreenFilter = ScreenFilter.ANY,
  /** Only show when the battery is at or below this percentage (0 = no rule). */
  val batteryBelow: Int = 0,
)

@Serializable
enum class ScreenFilter { ANY, LOCK_SCREEN, HOME_SCREEN }

/** Minutes after midnight; wraps past midnight when end < start (e.g. 18:00–06:00). */
@Serializable
data class TimeWindow(val startMinute: Int = 6 * 60, val endMinute: Int = 18 * 60)

@Serializable
enum class LayerType { MEDIA, GRADIENT, PARTICLES, TEXT }

@Serializable
data class Layer(
  val id: String,
  val name: String = "Layer",
  val type: LayerType = LayerType.MEDIA,
  /** For MEDIA layers. Null means an empty "pick media" slot (templates use these). */
  val assetId: String? = null,
  val visible: Boolean = true,
  val fit: Fit = Fit.COVER,
  val transform: Transform = Transform(),
  val blend: BlendMode = BlendMode.NORMAL,
  val effects: Effects = Effects(),
  val motion: Motion = Motion(),
  val playbackSpeed: Float = 1f,
  val gradient: GradientSpec? = null,
  val particles: ParticleSpec? = null,
  val text: TextSpec? = null,
  /** Cuts the layer to a shape or fades its edges. */
  val mask: MaskShape = MaskShape.NONE,
  /** How soft the mask edge is, 0..1. */
  val maskFeather: Float = 0.15f,
)

@Serializable
enum class MaskShape { NONE, CIRCLE, ROUNDED, HEART, STAR, SOFT_EDGES, FADE_TOP, FADE_BOTTOM }

@Serializable
enum class Fit { COVER, CONTAIN, STRETCH, BLUR_FILL }

/** x/y move the layer centre by a fraction of the screen width/height; rotation is clockwise degrees. */
@Serializable
data class Transform(
  val x: Float = 0f,
  val y: Float = 0f,
  val scale: Float = 1f,
  val rotation: Float = 0f,
  val opacity: Float = 1f,
)

@Serializable
enum class BlendMode { NORMAL, ADD, SCREEN, MULTIPLY }

/** All neutral by default. Ranges are what the editor sliders use. */
@Serializable
data class Effects(
  val blur: Float = 0f, // 0..1
  val brightness: Float = 0f, // -1..1
  val contrast: Float = 1f, // 0..2
  val saturation: Float = 1f, // 0..2
  val hue: Float = 0f, // -180..180 degrees
  val tintColor: Int = 0xFFFF9800.toInt(),
  val tintAmount: Float = 0f, // 0..1
  val vignette: Float = 0f, // 0..1
  val grain: Float = 0f, // 0..1
  val pixelate: Float = 0f, // 0..1
  val chromatic: Float = 0f, // 0..1
  val wave: Float = 0f, // 0..1
  val waveSpeed: Float = 1f, // 0..4
) {
  val isTimeBased: Boolean get() = grain > 0f || wave > 0f
}

@Serializable
data class Motion(
  /** How far the layer slides across all home-screen pages, as a fraction of screen width. */
  val swipeDepth: Float = 0f,
  /** How far the layer moves at full phone tilt, as a fraction of the screen. Negative = opposite way. */
  val tiltDepth: Float = 0f,
  val preset: MotionPreset = MotionPreset.NONE,
  val presetAmount: Float = 0.5f, // 0..1
  val presetPeriodSec: Float = 8f,
  val keyframes: List<KeyframeTrack> = emptyList(),
) {
  val isTimeBased: Boolean get() = preset != MotionPreset.NONE || keyframes.any { it.keys.size > 1 }
}

@Serializable
enum class MotionPreset { NONE, KEN_BURNS, FLOAT, PULSE, SWAY, SPIN, BREATHE, DRIFT }

@Serializable
data class KeyframeTrack(
  val property: AnimProperty,
  val keys: List<Keyframe> = emptyList(),
  val loop: LoopMode = LoopMode.LOOP,
  val easing: Easing = Easing.EASE_IN_OUT,
)

@Serializable
data class Keyframe(val timeSec: Float, val value: Float)

/** X/Y add a screen fraction, SCALE and OPACITY multiply, ROTATION adds degrees. */
@Serializable
enum class AnimProperty { X, Y, SCALE, ROTATION, OPACITY }

@Serializable
enum class LoopMode { LOOP, PING_PONG, ONCE }

@Serializable
enum class Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT }

@Serializable
data class GradientSpec(
  val colors: List<Int> = listOf(0xFF1A2980.toInt(), 0xFF26D0CE.toInt()),
  val angleDeg: Float = 90f,
  val radial: Boolean = false,
  /** 0 = still; otherwise how fast the colours flow (cycles per minute). */
  val flowSpeed: Float = 0f,
)

@Serializable
enum class ParticleKind { SNOW, RAIN, BOKEH, STARS, FIREFLIES, BUBBLES }

@Serializable
data class ParticleSpec(
  val kind: ParticleKind = ParticleKind.SNOW,
  val count: Int = 160,
  val size: Float = 1f, // 0.25..3
  val speed: Float = 1f, // 0..3
  val wind: Float = 0f, // -1..1
  val color: Int = 0xFFFFFFFF.toInt(),
  val opacity: Float = 0.9f,
  /** Tilting the phone pushes the particles the way gravity would. */
  val followTilt: Boolean = false,
)

@Serializable
enum class TextFont { SANS, SERIF, MONO, CONDENSED, LIGHT }

/**
 * Tokens: {time} {date} {day} {month} {year} {hour} {minute} {battery} {quote} {countdown:YYYY-MM-DD}.
 */
@Serializable
data class TextSpec(
  val text: String = "{time}",
  /** Text height as a fraction of screen height. */
  val size: Float = 0.08f,
  val color: Int = 0xFFFFFFFF.toInt(),
  val font: TextFont = TextFont.LIGHT,
  val bold: Boolean = false,
  val shadow: Boolean = true,
  /** Outline thickness as a fraction of the text size (0 = none). */
  val outline: Float = 0f,
  val outlineColor: Int = 0xFF000000.toInt(),
  /** A FONT asset the user added; overrides [font]. */
  val fontAsset: String? = null,
)

@Serializable
enum class TransitionType {
  CUT, CROSSFADE, FADE_COLOR, SLIDE, PUSH, ZOOM_IN, ZOOM_OUT, FLIP_3D, CUBE, CIRCLE, WIPE, DISSOLVE, BLUR,
}

@Serializable
enum class Direction { LEFT, RIGHT, UP, DOWN }

@Serializable
data class Transition(
  val type: TransitionType = TransitionType.CROSSFADE,
  val durationMs: Int = 1200,
  val direction: Direction = Direction.LEFT,
  val color: Int = 0xFF000000.toInt(),
)

/** When the wallpaper moves to the next scene. Scenes with time windows / charging rules are picked automatically. */
@Serializable
data class SceneSwitching(
  val timerSec: Int = 0,
  val onUnlock: Boolean = false,
  val onDoubleTap: Boolean = false,
  val shuffle: Boolean = false,
)

@Serializable
enum class BatteryBehavior { KEEP, REDUCE, PAUSE }

@Serializable
data class PlaybackSettings(
  val fpsCap: Int = 30,
  val batterySaver: BatteryBehavior = BatteryBehavior.REDUCE,
  /** Zoom the wallpaper in a little while the notification shade is pulled down. */
  val zoomOnShade: Boolean = true,
  /** 0 = off; otherwise how strongly colours follow the time of day (warm mornings, golden evenings, blue nights). */
  val dayCycle: Float = 0f,
  /** What happens when you tap an empty spot on the home screen. */
  val touch: TouchEffect = TouchEffect(),
)

@Serializable
enum class TouchKind { NONE, RIPPLE, SPARKLES, GLOW }

@Serializable
data class TouchEffect(
  val kind: TouchKind = TouchKind.NONE,
  val color: Int = 0xFFFFFFFF.toInt(),
  /** 0.5..2 */
  val size: Float = 1f,
)
