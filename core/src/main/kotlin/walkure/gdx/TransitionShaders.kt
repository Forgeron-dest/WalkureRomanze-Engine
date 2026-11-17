package walkure.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.glutils.ShaderProgram

/**
 * GLSL Shader for authentic QLiE rule-based grayscale mask wipe transitions (`フェード\*.bmp` and
 * `フェード\*.b`). Evaluates `maskVal < progress` with a smooth threshold band to recreate smooth
 * fades, character silhouettes, curtains, wind wipes, and diagonals.
 */
object TransitionShaders {
  private const val VERTEX_SHADER =
    """
attribute vec4 a_position;
attribute vec4 a_color;
attribute vec2 a_texCoord0;
uniform mat4 u_projTrans;
varying vec4 v_color;
varying vec2 v_texCoords;

void main() {
    v_color = a_color;
    v_texCoords = a_texCoord0;
    gl_Position = u_projTrans * a_position;
}
"""

  private const val RULE_WIPE_FRAGMENT_SHADER =
    """
#ifdef GL_ES
precision mediump float;
#endif

varying vec4 v_color;
varying vec2 v_texCoords;

uniform sampler2D u_texture;     // Current scene / incoming frame
uniform sampler2D u_prevTexture; // Previous scene / outgoing frame
uniform sampler2D u_maskTexture; // Rule grayscale mask (0.0..1.0)
uniform float u_progress;        // 0.0 -> 1.0
uniform float u_feather;         // softness band (default 0.08)

void main() {
    vec4 newColor = texture2D(u_texture, v_texCoords);
    vec4 oldColor = texture2D(u_prevTexture, v_texCoords);
    float mask = texture2D(u_maskTexture, v_texCoords).r;

    float edge0 = clamp(u_progress - u_feather, 0.0, 1.0);
    float edge1 = clamp(u_progress + u_feather, 0.0, 1.0);
    float factor = smoothstep(edge0, edge1, mask);

    // factor = 1.0 -> oldColor, factor = 0.0 -> newColor
    gl_FragColor = mix(newColor, oldColor, factor) * v_color;
}
"""

  private const val MOSAIC_TRANSITION_FRAGMENT_SHADER =
    """
#ifdef GL_ES
precision mediump float;
#endif

varying vec4 v_color;
varying vec2 v_texCoords;

uniform sampler2D u_texture;     // Incoming frame
uniform sampler2D u_prevTexture; // Outgoing frame
uniform float u_progress;        // 0.0 -> 1.0
uniform vec2 u_resolution;       // Virtual resolution (1024, 768)

void main() {
    float bell = sin(clamp(u_progress, 0.0, 1.0) * 3.14159265);
    float blockPx = mix(1.0, 42.0, bell * bell);
    vec2 grid = max(vec2(1.0), u_resolution / blockPx);
    vec2 uv = (floor(v_texCoords * grid) + 0.5) / grid;

    vec4 oldColor = texture2D(u_prevTexture, uv);
    vec4 newColor = texture2D(u_texture, uv);
    float blendFactor = smoothstep(0.25, 0.75, u_progress);
    gl_FragColor = mix(oldColor, newColor, blendFactor) * v_color;
}
"""

  fun createRuleWipeShader(): ShaderProgram? {
    val shader = ShaderProgram(VERTEX_SHADER, RULE_WIPE_FRAGMENT_SHADER)
    if (!shader.isCompiled) {
      Gdx.app.error("TransitionShaders", "RuleWipe shader compilation failed:\n" + shader.log)
      shader.dispose()
      return null
    }
    return shader
  }

  fun createMosaicTransitionShader(): ShaderProgram? {
    val shader = ShaderProgram(VERTEX_SHADER, MOSAIC_TRANSITION_FRAGMENT_SHADER)
    if (!shader.isCompiled) {
      Gdx.app.error("TransitionShaders", "Mosaic shader compilation failed:\n" + shader.log)
      shader.dispose()
      return null
    }
    return shader
  }
}
