#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D SceneSampler;

in vec2 texCoord;

uniform float EdgeMix;
uniform float EdgeStart;
uniform float EdgeEnd;
uniform float EdgePower;

out vec4 fragColor;

void main() {
    vec4 blurred = texture(DiffuseSampler, texCoord);
    vec4 scene = texture(SceneSampler, texCoord);

    vec2 centered = texCoord * 2.0 - 1.0;
    float radial = length(centered);
    float edge = smoothstep(EdgeStart, EdgeEnd, radial);
    edge = pow(clamp(edge, 0.0, 1.0), EdgePower);
    float blurMix = clamp(edge * EdgeMix, 0.0, 1.0);

    vec3 mixed = mix(scene.rgb, blurred.rgb, blurMix);
    fragColor = vec4(mixed, scene.a);
}
