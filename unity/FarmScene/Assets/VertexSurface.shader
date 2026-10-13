Shader "Farm/VertexSurface" {
    Properties {
        _Color ("Tint", Color) = (1,1,1,1)
        _MainTex ("Albedo", 2D) = "white" {}
        _Glossiness ("Smoothness", Range(0,1)) = .2
    }
    SubShader {
        Tags { "RenderType"="Opaque" }
        CGPROGRAM
        #pragma surface surf Standard fullforwardshadows
        #pragma target 3.0
        sampler2D _MainTex; fixed4 _Color; half _Glossiness;
        struct Input { float2 uv_MainTex; float4 color : COLOR; };
        void surf(Input i, inout SurfaceOutputStandard o) {
            o.Albedo = tex2D(_MainTex,i.uv_MainTex).rgb * _Color.rgb * i.color.rgb;
            o.Smoothness = _Glossiness;
        }
        ENDCG
    }
    Fallback "Diffuse"
}
