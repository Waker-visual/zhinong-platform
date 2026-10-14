Shader "Farm/Foliage" {
    Properties { _Color ("Crop tint", Color) = (0.6,0.7,0.3,1) _Detail ("Near detail layer", Float) = 0 }
    SubShader {
        Tags { "RenderType"="Opaque" }
        Cull Off
        CGPROGRAM
        #pragma surface surf Standard vertex:vert addshadow fullforwardshadows
        #pragma target 3.0
        fixed4 _Color; float _Detail; float4 _CropDetailTarget;
        struct Input { float4 color : COLOR; float3 worldPos; };
        void vert(inout appdata_full v) {
            float3 world = mul(unity_ObjectToWorld,v.vertex).xyz;
            float sway = sin(world.x*.55 + world.z*.36 + _Time.y*1.3);
            v.vertex.x += sway * v.color.a * .13;
            v.vertex.z += cos(world.z*.7 + _Time.y) * v.color.a * .07;
        }
        void surf(Input i, inout SurfaceOutputStandard o) {
            // Fade before the edge of the streamed 24 m tiles; the light base mesh remains.
            float fade=1-smoothstep(34,46,distance(i.worldPos.xz,_CropDetailTarget.xz));
            float dither=frac(sin(dot(floor(i.worldPos.xz*48),float2(127.1,311.7)))*43758.5453);
            clip(lerp(1,fade,_Detail)-dither-.001);
            o.Albedo = _Color.rgb * i.color.rgb;
            o.Smoothness = .18;
            o.Occlusion = lerp(.65,1,i.color.a);
        }
        ENDCG
    }
    Fallback "Diffuse"
}
