Shader "Farm/Water" {
    Properties {
        _Color ("Deep water", Color) = (.16,.29,.30,1)
        _Shallow ("Grazing reflection tint", Color) = (.62,.75,.82,1)
        _Glossiness ("Smoothness", Range(0,1)) = .93
    }
    SubShader {
        Tags { "RenderType"="Opaque" }
        CGPROGRAM
        #pragma surface surf Standard fullforwardshadows
        #pragma target 3.0
        fixed4 _Color, _Shallow; half _Glossiness;
        struct Input { float3 worldPos; float3 viewDir; };
        float hash(float2 p) { return frac(sin(dot(p,float2(127.1,311.7)))*43758.5453); }
        float noise(float2 p) {
            float2 i=floor(p),f=frac(p);f=f*f*(3-2*f);
            return lerp(lerp(hash(i),hash(i+float2(1,0)),f.x),lerp(hash(i+float2(0,1)),hash(i+1),f.x),f.y);
        }
        float height(float2 p) {
            float t=_Time.y;
            return noise(p*.55+float2(t*.11,t*.07))*.6+noise(p*1.7-float2(t*.19,-t*.13))*.4;
        }
        void surf(Input i, inout SurfaceOutputStandard o) {
            float2 p=i.worldPos.xz;
            const float e=.08;
            float h=height(p);
            float3 n=normalize(float3((h-height(p+float2(e,0)))*1.4,(h-height(p+float2(0,e)))*1.4,1));
            o.Normal=n;
            float fresnel=pow(1-saturate(dot(normalize(i.viewDir),float3(0,0,1))),4);
            o.Albedo=lerp(_Color.rgb,_Shallow.rgb,fresnel*.55)*(.92+h*.12);
            o.Smoothness=_Glossiness;
            o.Metallic=.05;
        }
        ENDCG
    }
    Fallback "Diffuse"
}
