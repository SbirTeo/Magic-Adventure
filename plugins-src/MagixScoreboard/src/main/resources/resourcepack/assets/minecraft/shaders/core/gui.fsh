#version 330

// MagixScoreboard: vanilla "gui" fragment shader (Minecraft 26.2) plus the sidebar background
// recolor and border. The __SB_*__ placeholders are replaced by the plugin from config.yml
// (sidebar-background.*): changing them rebuilds the resource pack.

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
flat in int sbKind;
in vec2 sbUV;
in vec2 sbGui;
in float sbOk;

out vec4 fragColor;

// Background of title and body (same color and opacity for both). Alpha 0 = no background.
const vec4 SB_BACKGROUND = vec4(__SB_R__, __SB_G__, __SB_B__, __SB_A__);
// Border on the top, left and bottom sides (never the right one), in GUI pixels. 0 = no border.
const float SB_BORDER = __SB_BORDER__;
// Horizontal gradient of the border, left to right (like the site: linear-gradient(90deg, ...)).
const vec3 SB_BORDER_START = vec3(__SB_C1_R__, __SB_C1_G__, __SB_C1_B__);
const vec3 SB_BORDER_END = vec3(__SB_C2_R__, __SB_C2_G__, __SB_C2_B__);
const float SB_BORDER_ALPHA = __SB_BORDER_A__;

void main() {
    vec4 color = vertexColor;
    // Derivatives out of any branch: they must be computed by the whole 2x2 pixel block.
    float width = abs(dFdx(sbGui.x) / max(abs(dFdx(sbUV.x)), 1e-6));
    float height = abs(dFdy(sbGui.y) / max(abs(dFdy(sbUV.y)), 1e-6));

    if (sbKind != 0) {
        color = SB_BACKGROUND;
        if (SB_BORDER > 0.0 && sbOk > 0.999) {
            float left = sbUV.x * width;
            float fromTop = sbUV.y * height;
            float fromBottom = (1.0 - sbUV.y) * height;
            // Title: left + top. Body: left + bottom. Together they outline the whole sidebar.
            bool edge = left < SB_BORDER
                    || (sbKind == 2 && fromTop < SB_BORDER)
                    || (sbKind == 1 && fromBottom < SB_BORDER);
            if (edge) {
                color = vec4(mix(SB_BORDER_START, SB_BORDER_END, clamp(sbUV.x, 0.0, 1.0)), SB_BORDER_ALPHA);
            }
        }
    }

    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
