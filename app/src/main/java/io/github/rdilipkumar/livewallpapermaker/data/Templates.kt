package io.github.rdilipkumar.livewallpapermaker.data

import io.github.rdilipkumar.livewallpapermaker.engine.model.BlendMode
import io.github.rdilipkumar.livewallpapermaker.engine.model.Direction
import io.github.rdilipkumar.livewallpapermaker.engine.model.Effects
import io.github.rdilipkumar.livewallpapermaker.engine.model.Fit
import io.github.rdilipkumar.livewallpapermaker.engine.model.GradientSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.Motion
import io.github.rdilipkumar.livewallpapermaker.engine.model.MotionPreset
import io.github.rdilipkumar.livewallpapermaker.engine.model.ParticleKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.ParticleSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.SceneSwitching
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextFont
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.TimeWindow
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transform
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transition
import io.github.rdilipkumar.livewallpapermaker.engine.model.TransitionType
import java.util.UUID

object Ids {
  fun new(prefix: String): String = prefix + "-" + UUID.randomUUID().toString().substring(0, 8)
}

/** Starting points. Media layers without an asset are "slots" the user fills in the editor. */
data class Template(
  val id: String,
  val name: String,
  val description: String,
  val slots: Int,
  val build: () -> Project,
)

object Templates {

  private fun project(name: String, scenes: List<Scene>, switching: SceneSwitching = SceneSwitching()): Project {
    val now = System.currentTimeMillis()
    return Project(id = Ids.new("p"), name = name, scenes = scenes, switching = switching, createdAt = now, updatedAt = now)
  }

  private fun scene(name: String, vararg layers: Layer, transition: Transition = Transition(), bg: Int = 0xFF000000.toInt()) =
    Scene(id = Ids.new("s"), name = name, layers = layers.toList(), transitionIn = transition, background = bg)

  fun slot(name: String, motion: Motion = Motion(), fit: Fit = Fit.COVER, effects: Effects = Effects(), transform: Transform = Transform()) =
    Layer(id = Ids.new("l"), name = name, type = LayerType.MEDIA, fit = fit, motion = motion, effects = effects, transform = transform)

  fun gradient(colors: List<Long>, angle: Float = 90f, flow: Float = 0f, radial: Boolean = false, motion: Motion = Motion()) =
    Layer(
      id = Ids.new("l"), name = "Gradient", type = LayerType.GRADIENT, motion = motion,
      gradient = GradientSpec(colors.map { it.toInt() }, angle, radial, flow),
    )

  fun particles(kind: ParticleKind, count: Int, color: Long = 0xFFFFFFFF, size: Float = 1f, speed: Float = 1f, wind: Float = 0f, blend: BlendMode = BlendMode.NORMAL, motion: Motion = Motion()) =
    Layer(
      id = Ids.new("l"), name = kind.name.lowercase().replaceFirstChar { it.uppercase() }, type = LayerType.PARTICLES,
      particles = ParticleSpec(kind, count, size, speed, wind, color.toInt()), blend = blend, motion = motion,
    )

  fun text(value: String, y: Float, size: Float = 0.08f, font: TextFont = TextFont.LIGHT, motion: Motion = Motion()) =
    Layer(
      id = Ids.new("l"), name = "Text", type = LayerType.TEXT, transform = Transform(y = y), motion = motion,
      text = TextSpec(text = value, size = size, font = font),
    )

  /** Shown by the wallpaper before anything was applied. */
  fun fallbackWallpaper(): Project = snowyNight().copy(id = "fallback")

  private fun snowyNight() = project(
    "Snowy night",
    listOf(
      scene(
        "Night",
        gradient(listOf(0xFF0B1026, 0xFF1B2A59, 0xFF3E4C8A), angle = 90f, motion = Motion(swipeDepth = 0.04f, tiltDepth = 0.02f)),
        particles(ParticleKind.STARS, 140, size = 0.8f, motion = Motion(swipeDepth = 0.06f, tiltDepth = 0.03f)),
        text("{time}", -0.28f, 0.11f, motion = Motion(tiltDepth = -0.015f)),
        text("{day}, {date}", -0.20f, 0.028f, TextFont.SANS, motion = Motion(tiltDepth = -0.015f)),
        particles(ParticleKind.SNOW, 220, size = 1f, speed = 1f, wind = 0.2f, motion = Motion(swipeDepth = 0.12f, tiltDepth = 0.06f)),
      ),
    ),
  )

  val all: List<Template> = listOf(
    Template("blank", "Blank", "Start from an empty canvas.", 0) {
      project("My wallpaper", listOf(scene("Scene 1")))
    },
    Template("snow", "Snowy night", "Night sky, falling snow and a clock. No media needed.", 0) { snowyNight() },
    Template("aurora", "Aurora", "Flowing colours with twinkling stars.", 0) {
      project(
        "Aurora",
        listOf(
          scene(
            "Aurora",
            gradient(listOf(0xFF02111B, 0xFF0B6E4F, 0xFF3A86FF, 0xFF8338EC), angle = 70f, flow = 2f, motion = Motion(swipeDepth = 0.05f, tiltDepth = 0.03f)),
            particles(ParticleKind.STARS, 120, size = 0.7f, blend = BlendMode.ADD, motion = Motion(swipeDepth = 0.08f, tiltDepth = 0.05f)),
            text("{time}", -0.3f, 0.1f),
          ),
        ),
      )
    },
    Template("rain", "Rainy evening", "Moody colours, rain streaks, soft lights.", 0) {
      project(
        "Rainy evening",
        listOf(
          scene(
            "Rain",
            gradient(listOf(0xFF1F2A36, 0xFF34495E, 0xFF5D6D7E), angle = 100f),
            particles(ParticleKind.BOKEH, 26, color = 0xFFFFC56E, size = 1.2f, speed = 0.6f, blend = BlendMode.SCREEN, motion = Motion(tiltDepth = 0.04f)),
            particles(ParticleKind.RAIN, 260, color = 0xFFDDE7F0, speed = 1.1f, wind = -0.25f, motion = Motion(tiltDepth = 0.08f)),
          ),
        ),
      )
    },
    Template("fireflies", "Fireflies", "Glowing fireflies drifting in the dark.", 0) {
      project(
        "Fireflies",
        listOf(
          scene(
            "Forest",
            gradient(listOf(0xFF03140F, 0xFF0D2B1F, 0xFF173F2A), radial = true),
            particles(ParticleKind.BOKEH, 18, color = 0xFF7BE495, size = 1.4f, speed = 0.4f, blend = BlendMode.SCREEN, motion = Motion(tiltDepth = 0.03f)),
            particles(ParticleKind.FIREFLIES, 70, color = 0xFFE9FF70, size = 1f, speed = 1f, blend = BlendMode.ADD, motion = Motion(tiltDepth = 0.09f, swipeDepth = 0.1f)),
          ),
        ),
      )
    },
    Template("video", "Single video", "One video or photo that fills the screen and slides as you swipe.", 1) {
      project("Video wallpaper", listOf(scene("Main", slot("Video or photo", Motion(swipeDepth = 0.06f)))))
    },
    Template("depth", "Depth parallax", "Three layers that move at different depths when you tilt the phone. Use a cut-out PNG on top.", 3) {
      project(
        "Depth parallax",
        listOf(
          scene(
            "Depth",
            slot("Background", Motion(swipeDepth = 0.04f, tiltDepth = 0.025f, preset = MotionPreset.KEN_BURNS, presetAmount = 0.3f, presetPeriodSec = 24f)),
            slot("Middle (PNG)", Motion(swipeDepth = 0.07f, tiltDepth = 0.06f), fit = Fit.CONTAIN),
            particles(ParticleKind.BOKEH, 14, size = 1f, speed = 0.5f, blend = BlendMode.SCREEN, motion = Motion(tiltDepth = 0.09f)),
            slot("Front (PNG)", Motion(swipeDepth = 0.11f, tiltDepth = 0.11f), fit = Fit.CONTAIN, transform = Transform(y = 0.18f)),
          ),
        ),
      )
    },
    Template("tilt", "Video + tilt", "A video that leans with your phone, with a soft vignette.", 1) {
      project(
        "Video + tilt",
        listOf(scene("Main", slot("Video", Motion(tiltDepth = 0.06f, swipeDepth = 0.04f), effects = Effects(vignette = 0.45f)))),
      )
    },
    Template("daynight", "Day & night", "One scene by day, another by night. Switches on its own.", 2) {
      val day = scene("Day", slot("Day video or photo", Motion(swipeDepth = 0.05f)), transition = Transition(TransitionType.CROSSFADE, 2500))
        .copy(timeWindow = TimeWindow(6 * 60, 18 * 60))
      val night = scene("Night", slot("Night video or photo", Motion(swipeDepth = 0.05f)), transition = Transition(TransitionType.CROSSFADE, 2500))
        .copy(timeWindow = TimeWindow(18 * 60, 6 * 60))
      project("Day & night", listOf(day, night))
    },
    Template("slideshow", "3D slideshow", "Three scenes that flip in 3D every 20 seconds or on double-tap.", 3) {
      val t = Transition(TransitionType.FLIP_3D, 1400, Direction.LEFT)
      project(
        "3D slideshow",
        (1..3).map { i ->
          scene("Slide $i", slot("Slide $i", Motion(preset = MotionPreset.KEN_BURNS, presetAmount = 0.5f, presetPeriodSec = 20f, swipeDepth = 0.04f)), transition = t)
        },
        SceneSwitching(timerSec = 20, onDoubleTap = true),
      )
    },
    Template("intro", "Unlock intro", "A short clip plays every time you unlock, then your main wallpaper returns.", 2) {
      val intro = scene("Intro", slot("Intro clip"), transition = Transition(TransitionType.CIRCLE, 700)).copy(isIntro = true)
      val main = scene("Main", slot("Main video or photo", Motion(swipeDepth = 0.05f)), transition = Transition(TransitionType.CROSSFADE, 900))
      project("Unlock intro", listOf(main, intro))
    },
  )
}
