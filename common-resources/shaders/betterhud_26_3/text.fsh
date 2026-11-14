#version 330
#extension GL_ARB_separate_shader_objects : require

#CreateConstant

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif

uniform sampler2D Sampler0;

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in vec4 bhClip;
layout(location = 5) in vec2 bhLocalV;
layout(location = 6) in vec4 bhRectV;
layout(location = 7) in vec4 bhFillV;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Signed distance to a rounded rectangle of the element's own pixels: <= 0 is inside.
float bhRoundRect(vec2 p, vec2 lo, vec2 hi, float radius) {
    vec2 q = abs(p - (lo + hi) * 0.5) - ((hi - lo) * 0.5 - radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

#GenerateOtherDefinedMethod

vec4 calculateFinalColor(vec4 color) {
#ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
#endif

#if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)

#ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
#else
    vec4 fogColor = FogColor;
#endif

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif

    return color;
}

void main() {
#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif

    vec4 color = texColor * vertexColor * ColorModulator;

    #GenerateOtherMainMethod

    //bhFillV: x = enabled, y = feather width, z = corner radius, w = swept angle (a pie fill, 0 for a
    //rectangular one). bhRectV is the region in the element's own pixels (0 .. 2 * its half size), so
    //the test never touches screen coordinates.
    if (bhFillV.x > 0.5) {
        float bhCover;
        if (abs(bhFillV.w) > 0.001) {
            // Pie: 0 at twelve o'clock, growing clockwise; a negative sweep goes the other way round.
            float bhRel = mod(atan(bhLocalV.y, bhLocalV.x) + 1.570796327, 6.283185307);
            float bhSweep = bhFillV.w;
            if (bhSweep < 0.0) {
                bhRel = 6.283185307 - bhRel;
                bhSweep = -bhSweep;
            }
            // The feather is given in pixels, so turn it into the angle it covers at this radius. A zero
            // one would make smoothstep divide by zero (undefined in GLSL), so it keeps a sub-pixel floor.
            float bhFeather = max(bhFillV.y, 0.5) / max(length(bhLocalV), 1.0);
            bhCover = 1.0 - smoothstep(bhSweep - bhFeather, bhSweep, bhRel);
        } else if (bhFillV.y > 0.0) {
            bhCover = 1.0 - smoothstep(-bhFillV.y, bhFillV.y, bhRoundRect(bhLocalV, bhRectV.xy, bhRectV.zw, bhFillV.z));
        } else {
            // A hard edge (no `clip-soft`): keep the pixels inside the region with a plain comparison.
            // Feeding smoothstep two equal bounds would be undefined and could cover the whole quad.
            bhCover = bhRoundRect(bhLocalV, bhRectV.xy, bhRectV.zw, bhFillV.z) <= 0.0 ? 1.0 : 0.0;
        }
        if (bhFillV.y <= 0.0) {
            if (bhCover < 0.5) discard;
        } else {
            color.a *= bhCover;
        }
    }

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

#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    fragColor = calculateFinalColor(color);
#endif
}
