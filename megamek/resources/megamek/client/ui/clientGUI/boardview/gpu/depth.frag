#version 330 core
// libGDX 1.14.2's DepthShader fragment stage (Apache License 2.0), converted to core-profile GLSL: for depth
// passes whose configuration names no fragment stage (GpuTreeInstances.depthProvider).
layout(location = 0) out vec4 fragColor;
#ifdef GL_ES 
#define LOWP lowp
#define MED mediump
#define HIGH highp
precision mediump float;
#else
#define MED
#define LOWP
#define HIGH
#endif

#if defined(diffuseTextureFlag) && defined(blendedFlag)
#define blendedTextureFlag
in MED vec2 v_texCoords0;
uniform sampler2D u_diffuseTexture;
uniform float u_alphaTest;
#endif

#ifdef PackedDepthFlag
in HIGH float v_depth;
#endif //PackedDepthFlag

void main() {
	#ifdef blendedTextureFlag
		if (texture(u_diffuseTexture, v_texCoords0).a < u_alphaTest)
			discard;
	#endif // blendedTextureFlag
	
	#ifdef PackedDepthFlag
		HIGH float depth = v_depth;
		const HIGH vec4 bias = vec4(1.0 / 255.0, 1.0 / 255.0, 1.0 / 255.0, 0.0);
		HIGH vec4 color = vec4(depth, fract(depth * 255.0), fract(depth * 65025.0), fract(depth * 16581375.0));
		fragColor = color - (color.yzww * bias);
	#endif //PackedDepthFlag
}
