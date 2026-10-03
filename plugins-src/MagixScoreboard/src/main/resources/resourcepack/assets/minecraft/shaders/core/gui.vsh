#version 330

// MagixScoreboard: vanilla "gui" vertex shader (Minecraft 26.2, taken from the real client jar) plus
// the recognition of the two sidebar background quads, so that gui.fsh can recolor them and draw a
// border. Every other GUI fill goes through untouched.
//
// How the sidebar is drawn in 26.2 (read from the client bytecode, Hud.displayScoreboardSidebar):
// two GuiGraphicsExtractor.fill calls with pure black, alpha Options.getBackgroundColor(0.4f) for
// the title row and 0.3f for the body. Both quads end at x = guiWidth - 1. With L lines, H = 9L and
// half = guiHeight / 2 (integer division): the body spans y = half - 6L - 1 .. half + 3L (bottom =
// half + H/3, top = bottom - H - 1), the title the 9 pixels right above it, half - 6L - 10 .. half - 6L - 1.
//
// Which corner a vertex is comes from its POSITION only, never from gl_VertexID: the client packs the
// whole GUI in one shared vertex buffer and draws each batch with a base vertex that is not always a
// multiple of 4, so gl_VertexID % 4 was off depending on what else was on screen (chat, items,
// titles...) and the border showed up only sometimes. Right: x on guiWidth - 1. Body top/bottom: the
// screen middle splits them. Title top/bottom: half - y - 1 is 6L at its bottom and 6L + 9 at its
// top, so modulo 6 it is 0 or 3 and tells them apart without knowing L.

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl and projection.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
// 0 = not the sidebar, 1 = sidebar body, 2 = sidebar title (the same on all 4 corners of a quad).
flat out int sbKind;
// Position inside the quad, 0..1 (x: 0 left, 1 right; y: 0 top, 1 bottom).
out vec2 sbUV;
// Position in GUI pixels: with sbUV, gui.fsh derives the quad size (border thickness in pixels).
out vec2 sbGui;
// 1 when the corner recognition is trusted; anything less and gui.fsh draws no border (background only).
out float sbOk;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    sbKind = 0;
    sbUV = vec2(0.0);
    sbGui = vec2(0.0);
    sbOk = 0.0;

    bool black = all(lessThan(Color.rgb, vec3(0.002)));
    bool body = black && abs(Color.a - 76.0 / 255.0) < 0.002;
    bool title = black && abs(Color.a - 102.0 / 255.0) < 0.002;
    if (body || title) {
        vec4 p = ModelViewMat * vec4(Position, 1.0);
        float gw = abs(2.0 / ProjMat[0][0]);
        float gh = abs(2.0 / ProjMat[1][1]);
        bool right = p.x > gw - 1.5;
        bool top;
        bool ok = true;
        if (body) {
            top = p.y < gh * 0.5;
        } else {
            float midY = floor(floor(gh + 0.5) * 0.5);
            float r = mod(midY - floor(p.y + 0.5) - 1.0, 6.0);
            top = abs(r - 3.0) < 0.5;
            // Neither 0 nor 3: not the layout described above (another client?). Keep the
            // recolored background, skip the border.
            ok = top || r < 0.5 || r > 5.5;
        }
        // Sidebar moved vertically (config sidebar-position.offset-y): the text shader (text.vsh of
        // MagixFactions) moves the lines by the same amount.
        gl_Position = ProjMat * vec4(p.x, p.y + __SB_SHIFT__, p.z, p.w);
        sbKind = body ? 1 : 2;
        sbUV = vec2(right ? 1.0 : 0.0, top ? 0.0 : 1.0);
        sbGui = p.xy;
        sbOk = ok ? 1.0 : 0.0;
    }
}
