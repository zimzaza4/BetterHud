#version 330

#CreateConstant

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <fog.glsl>
#endif

#if SHADER_VERSION >= 2
#moj_import <dynamictransforms.glsl>
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#else
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in float vertexDistance;
#endif

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;
in vec4 bhClip;

out vec4 fragColor;

#GenerateOtherDefinedMethod

void main() {
#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif
#ifdef IS_SEE_THROUGH
    vec4 color = texColor * vertexColor;
#else
    vec4 color = texColor * vertexColor * ColorModulator;
#endif

    #GenerateOtherMainMethod

    //bhClip: xy = pixel offset from the element center, z = inner radius, w = outer radius (0 = no clip)
    //  inner 0    -> disc (keep everything inside the radius)
    //  inner > 0  -> ring (keep only the [inner, outer] band)
    if (bhClip.w > 0.0) {
        float bhClipLen = length(bhClip.xy);
        if (bhClipLen > bhClip.w || bhClipLen < bhClip.z) {
            discard;
        }
    }

    if (color.a < 0.1) {
        discard;
    }
#ifdef IS_SEE_THROUGH
    fragColor = color * ColorModulator;
#elif defined(IS_GUI)
    fragColor = color;
#elif SHADER_VERSION >= 2
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#else
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
#endif
}
