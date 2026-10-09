#version 330

// 26.2 stages every GUI draw in one shared vertex buffer and issues each of them with a
// baseVertex offset, so gl_VertexID is no longer the index inside the draw: extra HUD
// geometry (item durability bar, hotbar cooldown overlay) shifts it and changes which
// corner every glyph vertex is taken to be. gl_BaseVertexARB is not an option either -
// Iris and other GLSL parsers reject the ARB-suffixed builtin.
//
// 26.2 textures every glyph with a 0.01 inset (FontTexture.add), so the fractional part
// of UV0 * 256 is ~0.01 on the left/top vertex of the quad and ~0.99 on the right/bottom
// one. That identifies the corner with no vertex index and no extension at all, on every
// backend. Older versions keep the plain gl_VertexID % 4 mapping.

#CreateConstant

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <fog.glsl>
#moj_import <sample_lightmap.glsl>
#endif

#if SHADER_VERSION >= 2
#moj_import <dynamictransforms.glsl>
#moj_import <projection.glsl>
#moj_import <globals.glsl>
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#else
uniform mat4 ProjMat;
uniform mat4 ModelViewMat;
uniform int FogShape;
out float vertexDistance;
uniform vec2 ScreenSize;
uniform float GameTime;
#endif

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
#endif

uniform sampler2D Sampler0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
#endif

out vec4 vertexColor;
out vec2 texCoord0;
out vec4 bhClip;
// Custom clip region (`clip-rect`), carried in element pixels from the element's top-left corner.
// bhFillV = (enabled, feather width, corner radius, unused): a fragment which sees 0 does nothing,
// so an element without a region costs one branch and no case-body flag.
out vec2 bhLocalV;
out vec4 bhRectV;
out vec4 bhFillV;

float bhEase(float t, float mode) {
    if (mode < 0.5) return t * t * (3.0 - 2.0 * t);
    else if (mode < 1.5) return t;
    return 1.0 - (1.0 - t) * (1.0 - t);
}

// Ticks elapsed since the update which wrote `tick`, for the payload layouts below. Both periods
// divide 24000, so GameTime * 24000 (the world time in ticks) survives the day counter.
float bhAge(int tick, float period) {
    float half = period * 0.5;
    return mod(GameTime * 24000.0 - float(tick) + half, period) - half;
}

bool range(float t, float m1, float m2) {
    return t >= m1 && t <= m2;
}

bool range(vec2 t, vec2 m1, vec2 m2) {
    return range(t.x, m1.x, m2.x) && range(t.y, m1.y, m2.y);
}

bool range(vec3 t, vec3 m1, vec3 m2) {
    return range(t.x, m1.x, m2.x) && range(t.y, m1.y, m2.y) && range(t.z, m1.z, m2.z);
}

bool checkElement(float z) {
    if (z == 0) return true; //<=1.20.4 vanilla
    else if (z == 1000) return true; //>=1.20.5 vanilla
    else if (z == -90) return true; //<=1.20.4 forge
    else if (z == 2800) return true; //neoforge
    return false;
}

float fogDistance(vec3 pos, int shape) {
    if (shape == 0) {
        return length(pos);
    } else {
        float distXZ = length(pos.xz);
        float distY = abs(pos.y);
        return max(distXZ, distY);
    }
}

#GenerateOtherDefinedMethod

void main() {
    vec3 pos = Position;
    vec2 ui = ceil(2 / vec2(ProjMat[0][0], -ProjMat[1][1]));
    vec2 uiScreen = ui / ScreenSize;
    vec3 color = Color.xyz;
    vertexColor = Color;
    bool bhRotOn = false;
    float bhRot = 0.0;
    vec2 bhRotHalf = vec2(0.0);
    // Offset from the centre of this glyph to the point the element turns around: zero for a glyph
    // covering the whole element, the offset to the element's centre for a tile of a cut image, so
    // that the pieces do not turn around different points.
    // The define lets a case body assign it - a hand-edited copy of this file which predates it
    // compiles the assignment out instead of failing.
    #define BH_ROT_ANCHOR
    vec2 bhRotAnchor = vec2(0.0);
    int bhPayload = 0;
    bool bhRotateOffset = false;   // rotate the payload offset together with the element
    float bhClipIn = 0.0;
    float bhClipOut = 0.0;
    bool bhClipHasOrigin = false;
    vec2 bhClipOrigin = vec2(0.0);
    float bhAnchorX = 0.0;
    float bhAnchorY = 0.0;
    // Smoothing and the custom clip region. The case body assigns these, so they are part of an
    // element's identity; only what changes every update travels in the payload.
    float bhSmoothDur = 0.0;
    float bhSmoothEase = 0.0;
    float bhOmegaScale = 1.0;
    // Whether the eight bit payload drives an angle (`bhPayload = 4`). An angle gets a much longer ramp
    // that way - its tick counts to 240 where the four bit one counts to 16 - which is what a fast turn
    // needs: a continued ramp is off by the step over the ramp, so a longer ramp shrinks the error.
    bool bhValueRot = false;
    bool bhFillOn = false;
    vec2 bhFillMin = vec2(0.0);
    vec2 bhFillMax = vec2(0.0);
    float bhFillRadius = 0.0;
    float bhFillSoft = 0.0;
    int bhFillAxis = -1;
    bool bhFillInvert = false;
    // `bhFillFollow` true reads the region from the transformed vertex, so it travels with the scrolled or
    // rotated content; false (what `clip-follow: base`, the default, emits) reads it from the untransformed
    // one, which pins the rectangle to the element's placement. `bhValueUse` says what the payload drives:
    // 0 = the region's boundary, 1 = pos.x, 2 = pos.y (a content scroll).
    bool bhFillFollow = true;
    int bhValueUse = 0;
    float bhValueScale = 0.0;
    // Half the element the region belongs to: adding it to pos - bhPivot turns the pivot (the element's
    // centre) into the element's top-left corner, which is what `clip-rect` counts from.
    vec2 bhElemHalf = vec2(0.0);
    vec2 bhScrollV = vec2(0.0);
    bhClip = vec4(0.0);
    bhLocalV = vec2(0.0);
    bhRectV = vec4(0.0);
    bhFillV = vec4(0.0);
    if (pos.y >= ui.y && ProjMat[3].x == -1) {
        int bit = int(pos.y) >> HEIGHT_BIT;

        if (((bit >> MAX_BIT) & 1) == 1) {

            int id = bit - (1 << MAX_BIT);

            pos.x -= 0.5 * ui.x;
            pos.y -= (bit << HEIGHT_BIT) + ADD_OFFSET + DEFAULT_OFFSET;

            float xGui = 0;
            float yGui = 0;
            float layer = 0;
            float opacity = 1;
            bool outline = false;
            int property = 0;

            // The cases come as a tree of `if (id <= ...)` instead of a switch: some drivers lower a
            // switch into an if/else chain nested once per case and refuse more than 64 levels, which a
            // pack of about 62 elements reaches. A copy of this file edited by hand predates the define
            // and keeps its own switch - the cases then come as `case` labels (the #else branch below).
            #define BH_FLAT_LAYOUT
            #CreateLayout

#if SHADER_VERSION < 1
            vertexColor = (checkElement(pos.z) && !outline) ? vec4(0) : Color * vec4(1, 1, 1, opacity);
#else
            vertexColor = Color * vec4(1, 1, 1, opacity);
#endif

            //Wave
            if ((property & 1) > 0) {
                pos.y += 4 * sin((GameTime * 1200 + pos.x / ui.x) * 3.1415 * 2);
            }
            //Rainbow
            if ((property & 2) > 0) {
                int hash = int(pos.x) * int(pos.y);
                float time = GameTime * 1200;
                hash = 31 * (hash + int(vertexColor.x + time));
                float r = float(hash % 224 + 32) / 255;
                hash = 31 * (hash + int(vertexColor.y + time));
                float g = float(hash % 224 + 32) / 255;
                hash = 31 * (hash + int(vertexColor.z + time));
                float b = float(hash % 224 + 32) / 255;
                float maxValue = max(max(r, g), b);
                vertexColor = vec4(pow(r / maxValue, 3), pow(g / maxValue, 3), pow(b / maxValue, 3), vertexColor.w);
            }
            //Tiny rainbow
            if ((property & 4) > 0) {
                int hash = int(pos.x) * int(pos.y);
                float time = GameTime * 1200;
                hash = 31 * (hash + int(vertexColor.x + time));
                float r = vertexColor.x + float(hash % 64) / 255;
                hash = 31 * (hash + int(vertexColor.y + time));
                float g = vertexColor.y + float(hash % 64) / 255;
                hash = 31 * (hash + int(vertexColor.z + time));
                float b = vertexColor.z + float(hash % 64) / 255;
                vertexColor = vec4(r, g, b, vertexColor.w);
            }

            pos.x += xGui;
            pos.y += yGui;
            pos.z += layer;

        }
    } else {
//HideExp        vec3 exp = vec3(128.0, 255.0, 32.0);
//HideExp        if (ProjMat[3].x == -1 && range(pos.y, ui.y - 60, ui.y - 20) && range(pos.x, ui.x / 2 - 60, ui.x / 2 + 60) && (range(color, exp / 256, exp / 254) || color == vec3(0))) {
//HideExp            vertexColor = vec4(0);
//HideExp        }
    }
    if (bhRotOn) {
        vec2 bhOfs = vec2(0.0);
        float bhValue = 0.0;
        if (bhPayload == 1) {
            int bhBits = int(vertexColor.x * 255.0 + 0.5) * 256 + int(vertexColor.y * 255.0 + 0.5);
            bhRot = float(bhBits) / 65535.0 * 6.283185307;
        } else if (bhPayload == 2) {
            int bhRx = int(vertexColor.x * 255.0 + 0.5);
            int bhGy = int(vertexColor.y * 255.0 + 0.5);
            int bhBz = int(vertexColor.z * 255.0 + 0.5);
            bhOfs = vec2(
                float((bhRx << 4) + (bhGy >> 4)) - 2048.0,
                float(((bhGy & 15) << 8) + bhBz) - 2048.0
            );
        } else if (bhPayload == 3) {
            // R = angle (8 bit); G/B = the two axes (8 bit each, +-128). Read the raw Color
            // attribute: the case multiplied vertexColor by the element opacity, which would
            // scale these bytes. A fourth byte is not available - Minecraft takes the text
            // vertex alpha from the caller, not from the component colour (Font.prepareText).
            bhRot = Color.x * 6.283185307;
            bhOfs = vec2(Color.y * 255.0 - 128.0, Color.z * 255.0 - 128.0);
        } else if (bhPayload == 4) {
            // red/green = from/to in 0..1, blue = the tick it was sent: the value walks to the new one
            // on the GPU. Only three channels are writable (Minecraft takes the glyph alpha from the
            // caller), so a tween is the most one element can carry.
            float bhElapsed = bhAge(int(Color.b * 255.0 + 0.5), 240.0);
            float bhT = bhSmoothDur <= 0.0 ? 1.0 : clamp(bhElapsed / bhSmoothDur, 0.0, 1.0);
            float bhFrom = Color.r;
            float bhTo = Color.g;
            if (bhValueRot) {
                // An angle repeats every whole turn, so the endpoint to walk to is the nearest one rather
                // than whichever branch a wrapping placeholder produced.
                float bhArc = bhTo - bhFrom;
                if (bhArc > 0.5) bhArc -= 1.0;
                else if (bhArc < -0.5) bhArc += 1.0;
                bhRot = mix(bhFrom, bhFrom + bhArc, bhEase(bhT, bhSmoothEase)) * 6.283185307;
            } else {
                bhValue = mix(bhFrom, bhTo, bhEase(bhT, bhSmoothEase));
            }
        } else if (bhPayload == 5) {
            // red/green = an 11 bit value and a 5 bit tick, blue = the rate. A rotation reads the value as
            // an angle and the rate as radians per tick; a content scroll reads both as a position, which
            // is why this layout (and not the 8 bit tween) carries it. Alpha keeps the element's colour.
            int bhBits = int(Color.r * 255.0 + 0.5) * 256 + int(Color.g * 255.0 + 0.5);
            float bhNorm = float(bhBits >> 5) / 2047.0;
            // The rate is a square law (`sqrt(rate / range)` in the byte): a linear field spends its
            // resolution on the fast end, which leaves a slow turn stepping by pixels a tick.
            float bhRateQ = (Color.b * 255.0 - 128.0) / 128.0;
            float bhRate = sign(bhRateQ) * bhRateQ * bhRateQ;
            // Only the fraction of that age may be used. Both counters are whole ticks, so the client's
            // clock offset against the server is a whole number of ticks too: keeping it makes the client
            // extrapolate `offset + fraction` ticks and sail past a stop before the next payload yanks it
            // back. The fraction drops the offset and anchors the phase to the update it came from - not to
            // the client's own tick boundary, which `fract(GameTime * 24000)` mistakes for it.
            float bhAgeRaw = bhAge(bhBits & 31, 32.0);
            float bhTicks = abs(bhAgeRaw) < 16.0 ? fract(bhAgeRaw) : 0.0;
            if (bhValueUse > 0) {
                float bhOffset = (bhNorm * 2.0 - 1.0 + bhRate * bhTicks * 2.0) * bhValueScale;
                if (bhValueUse == 1) bhScrollV.x = bhOffset; else bhScrollV.y = bhOffset;
            } else {
                bhRot = bhNorm * 6.283185307 + bhRate * bhOmegaScale * bhTicks;
            }
        } else if (bhPayload == 6) {
            // Two absolute endpoints and the tick the update was made in. Nothing is estimated, so a change
            // of speed cannot overshoot, and the phase comes from the update rather than from the client's
            // own tick boundary.
            int bhBits = int(Color.r * 255.0 + 0.5) * 65536 + int(Color.g * 255.0 + 0.5) * 256
                + int(Color.b * 255.0 + 0.5);
            float bhFrom = float((bhBits >> 14) & 0x3FF) / 1023.0;
            float bhTo = float((bhBits >> 4) & 0x3FF) / 1023.0;
            float bhRampAge = bhAge(bhBits & 15, 16.0);
            float bhRamp = bhSmoothDur <= 0.0 ? 1.0 : bhEase(clamp(bhRampAge / bhSmoothDur, 0.0, 1.0), bhSmoothEase);
            // The content repeats every whole period, so an endpoint a period away is the same picture: take
            // the short way round, or the wrap a placeholder does at the seam would be read as motion.
            float bhDelta = bhTo - bhFrom;
            if (bhDelta > 0.5) bhDelta -= 1.0;
            else if (bhDelta < -0.5) bhDelta += 1.0;
            bhValue = bhFrom + bhDelta * bhRamp;
            if (bhValueUse > 0) {
                float bhOffset = (bhValue * 2.0 - 1.0) * bhValueScale;
                if (bhValueUse == 1) bhScrollV.x = bhOffset; else bhScrollV.y = bhOffset;
            } else {
                // A rotation takes the same two endpoints: an angle is a scalar, and its own period is what
                // the short way round above is for.
                bhRot = bhValue * 6.283185307;
            }
        }
        if (bhPayload > 0) {
            // The payload owns the colour bytes; draw the glyph plain white, keeping the alpha.
            vertexColor = vec4(1.0, 1.0, 1.0, vertexColor.w);
        }
#if SHADER_VERSION >= 3
        // corner from the glyph texture inset (see the note at the top of this file)
        vec2 bhCs = sign(vec2(fract(UV0.x * 256.0) - 0.5, fract(UV0.y * 256.0) - 0.5));
#else
        float bhCi = float(gl_VertexID % 4);
        vec2 bhCs = vec2((bhCi == 2.0 || bhCi == 3.0) ? 1.0 : -1.0, (bhCi == 1.0 || bhCi == 2.0) ? 1.0 : -1.0);
#endif
        vec2 bhPivot = pos.xy - bhCs * bhRotHalf + bhRotAnchor;
        float bhCa = cos(bhRot);
        float bhSa = sin(bhRot);
        if (bhRotateOffset) {
            bhOfs = vec2(bhCa * bhOfs.x - bhSa * bhOfs.y, bhSa * bhOfs.x + bhCa * bhOfs.y);
        }
        vec2 bhD = pos.xy - bhPivot;
        pos.xy = bhPivot + vec2(bhCa * bhD.x - bhSa * bhD.y, bhSa * bhD.x + bhCa * bhD.y) + bhOfs + bhScrollV;
        vec2 bhClipC = bhClipHasOrigin ? bhClipOrigin : bhPivot;
        bhClip = vec4(pos.xy - bhClipC, bhClipIn, bhClipOut);
        // Custom clip region: a rectangle in the element's own box, `bhLocalV` measured from its top-left
        // corner (0 .. 2 * bhElemHalf across). The default reading is a fixed window: it is built from
        // bhD, the vertex before the rotation and the scroll moved it, so the rectangle stays exactly
        // where the element was placed and the content passes behind it - what a minimap or a compass
        // window is. `clip-follow: content` builds it from pos instead, which makes the rectangle travel
        // with the picture (and keeps bhPivot, the centre of the whole element, as its origin).
        if (bhFillOn) {
            bhLocalV = (bhFillFollow ? pos.xy - bhPivot : bhD) + bhElemHalf;
            vec2 bhMin = bhFillMin;
            vec2 bhMax = bhFillMax;
            if (bhFillAxis == 0 || bhFillAxis == 1) {
                float bhStart = bhFillAxis == 0 ? bhMin.x : bhMin.y;
                float bhEnd = bhFillAxis == 0 ? bhMax.x : bhMax.y;
                float bhEdge = mix(bhStart, bhEnd, bhFillInvert ? 1.0 - bhValue : bhValue);
                if (bhFillAxis == 0) {
                    if (bhFillInvert) bhMin.x = bhEdge; else bhMax.x = bhEdge;
                } else {
                    if (bhFillInvert) bhMin.y = bhEdge; else bhMax.y = bhEdge;
                }
                bhRectV = vec4(bhMin, bhMax);
                bhFillV = vec4(1.0, bhFillSoft, bhFillRadius, 0.0);
            } else if (bhFillAxis == 3 || bhFillAxis == 4) {
                // Symmetric: the middle stays put and both edges move outwards with the value.
                bool bhOnX = bhFillAxis == 3;
                float bhMid = bhOnX ? (bhMin.x + bhMax.x) * 0.5 : (bhMin.y + bhMax.y) * 0.5;
                float bhHalf = (bhOnX ? bhMax.x - bhMin.x : bhMax.y - bhMin.y) * 0.5
                    * (bhFillInvert ? 1.0 - bhValue : bhValue);
                if (bhOnX) {
                    bhMin.x = bhMid - bhHalf;
                    bhMax.x = bhMid + bhHalf;
                } else {
                    bhMin.y = bhMid - bhHalf;
                    bhMax.y = bhMid + bhHalf;
                }
                bhRectV = vec4(bhMin, bhMax);
                bhFillV = vec4(1.0, bhFillSoft, bhFillRadius, 0.0);
            } else if (bhFillAxis == 2) {
                // A radial fill only moves the clip radius, so a disc or ring grows with the value.
                bhClip.w *= clamp(bhValue, 0.0, 1.0);
            } else if (bhFillAxis == 5) {
                // Pie: the fragment compares the angle of the pixel against this swept one, measured from
                // twelve o'clock. A negative sweep goes the other way round.
                bhRectV = vec4(bhMin, bhMax);
                bhFillV = vec4(1.0, bhFillSoft, bhFillRadius,
                    clamp(bhValue, 0.0, 1.0) * 6.283185307 * (bhFillInvert ? -1.0 : 1.0));
            } else {
                // A region which only clips - `clip-rect` without `clip-fill`: the fixed window a scrolling
                // strip slides behind. Measured on the final position (`pos` already carries bhOfs and
                // bhScrollV), so the window stays where the element was placed. `bhCs` cannot be used for
                // this: the corner sign it recovers only works while every corner lands on a different side
                // of half a texel, which a wide tiled picture does not, and the window would then be decided
                // per tile - the band travelling with the picture.
                vec2 bhWinC = bhClipHasOrigin ? bhClipOrigin : bhPivot;
                bhLocalV = (bhFillFollow ? bhD : pos.xy - bhWinC) + bhElemHalf;
                bhRectV = vec4(bhMin, bhMax);
                bhFillV = vec4(1.0, bhFillSoft, bhFillRadius, 0.0);
            }
        }
    }
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    vertexColor *= sample_lightmap(Sampler2, UV2);
#endif

    #GenerateOtherMainMethod

#if SHADER_VERSION >= 2
    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    #endif
#else
    vertexDistance = fogDistance(pos, FogShape);
#endif

    texCoord0 = UV0;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
}
