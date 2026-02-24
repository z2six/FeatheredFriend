#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 rgb = texture(DiffuseSampler, texCoord).rgb;
    fragColor = vec4(rgb, 1.0);
}
