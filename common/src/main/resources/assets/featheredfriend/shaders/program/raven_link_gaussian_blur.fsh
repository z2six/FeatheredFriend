#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
in vec2 sampleStep;

uniform float Radius;

out vec4 fragColor;

const int MAX_RADIUS = 12;

void main() {
    int radius = int(clamp(floor(Radius + 0.5), 1.0, float(MAX_RADIUS)));
    float sigma = max(Radius * 0.5, 1.0);
    float invTwoSigmaSq = 0.5 / (sigma * sigma);

    vec4 accum = texture(DiffuseSampler, texCoord);
    float totalWeight = 1.0;

    for (int i = 1; i <= MAX_RADIUS; i++) {
        if (i > radius) {
            break;
        }

        float x = float(i);
        float weight = exp(-(x * x) * invTwoSigmaSq);
        vec2 offset = sampleStep * x;
        accum += texture(DiffuseSampler, texCoord + offset) * weight;
        accum += texture(DiffuseSampler, texCoord - offset) * weight;
        totalWeight += 2.0 * weight;
    }

    fragColor = accum / totalWeight;
}
