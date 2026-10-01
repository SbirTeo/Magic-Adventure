#version 330

// MagixScoreboard: vanilla "gui" vertex shader (Minecraft 26.2, taken from the real client jar) plus
// the recognition of the two sidebar background quads, so that gui.fsh can recolor them and draw a
// border. Every other GUI fill goes through untouched.
//
// How the sidebar is drawn in 26.2 (read from the client bytecode, Hud.displayScoreboardSidebar):
// two GuiGraphicsExtractor.fill calls with pure black, alpha Options.getBackgroundColor(0.4f) for
// the title row and 0.3f for the body. fill() swaps the corners so that x0/y0 are the LARGER ones,
// and ColoredRectangleRenderState emits (x0,y0) (x0,y1) (x1,y1) (x1,y0): corner 0 = bottom-right,
// 1 = top-right, 2 = top-left, 3 = bottom-left. Both quads end at x = guiWidth - 1; the body spans
// the screen middle (bottom = guiHeight/2 + H/3, top = bottom - H), the title sits right above it.

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
        int corner = gl_VertexID % 4;
        bool right = corner <= 1;
        bool top = corner == 1 || corner == 2;
        // Cross-check the corner with what the position says for sure: a right corner lies on
        // guiWidth - 1, and the body is split by the screen middle. Any disagreement means the
        // vertex order is not the expected one: keep the recolored background, skip the border.
        bool rightByPos = p.x > gw - 1.5;
        bool ok = right == rightByPos;
        if (body) ok = ok && (top == (p.y < gh * 0.5));
        // Sidebar spostata in verticale (config sidebar-position.offset-y): le scritte le sposta della
        // stessa quantita' lo shader del testo (text.vsh di MagixFactions).
        gl_Position = ProjMat * vec4(p.x, p.y + __SB_SHIFT__, p.z, p.w);
        sbKind = body ? 1 : 2;
        sbUV = vec2(right ? 1.0 : 0.0, top ? 0.0 : 1.0);
        sbGui = p.xy;
        sbOk = ok ? 1.0 : 0.0;
    }
}
