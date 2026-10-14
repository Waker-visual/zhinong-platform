Shader "Farm/OrthoGround" {
    Properties {
        _MainTex ("Registered satellite imagery", 2D) = "white" {}
        _WorldRect ("West, south, width, depth", Vector) = (0,0,1,1)
        _Color ("Tint", Color) = (1,1,1,1)
        _Glossiness ("Smoothness", Range(0,1)) = 0.12
    }
    SubShader {
        Tags { "RenderType"="Opaque" }
        CGPROGRAM
        #pragma surface surf Standard fullforwardshadows
        #pragma target 3.0
        sampler2D _MainTex;
        float4 _WorldRect;
        fixed4 _Color;
        half _Glossiness;
        struct Input { float3 worldPos; };
        void surf(Input IN, inout SurfaceOutputStandard o) {
            float2 uv=(IN.worldPos.xz-_WorldRect.xy)/_WorldRect.zw;
            fixed3 groundColor=tex2D(_MainTex,uv).rgb;
            // The photograph already includes shadows. Lift its deepest tones instead of
            // multiplying those baked shadows by a second fully dark lighting pass.
            groundColor=lerp(groundColor,sqrt(max(groundColor,0)),.24);
            o.Albedo=groundColor*_Color.rgb;
            o.Emission=groundColor*_Color.rgb*.12;
            o.Smoothness=_Glossiness;
            o.Occlusion=1;
        }
        ENDCG
    }
    FallBack "Diffuse"
}
