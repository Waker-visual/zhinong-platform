Shader "Farm/Ground" {
    Properties {
        _Color ("Soil tint", Color) = (.32,.25,.16,1)
        _Rows ("Cultivated furrows", Float) = 1
        _Wet ("Standing water illustration", Range(0,1)) = 0
    }
    SubShader {
        Tags { "RenderType"="Opaque" }
        CGPROGRAM
        #pragma surface surf Standard fullforwardshadows
        #pragma target 3.0
        fixed4 _Color; float _Rows, _Wet;
        struct Input { float3 worldPos; };
        float hash(float2 p) { return frac(sin(dot(p,float2(127.1,311.7)))*43758.5453); }
        float noise(float2 p) {
            float2 i=floor(p),f=frac(p);f=f*f*(3-2*f);
            return lerp(lerp(hash(i),hash(i+float2(1,0)),f.x),lerp(hash(i+float2(0,1)),hash(i+1),f.x),f.y);
        }
        void surf(Input i, inout SurfaceOutputStandard o) {
            float2 p=i.worldPos.xz;
            float soil=noise(p*7)*.16+noise(p*.7)*.16+noise(p*.06)*.16;
            float rows=sin(p.x*10.47)*_Rows;
            o.Albedo=_Color.rgb*(.67+soil+rows*.10);
            float ripple=sin(p.x*2.3+_Time.y*.7)*cos(p.y*1.8+_Time.y*.5);
            o.Normal=normalize(float3(rows*.14,lerp((noise(p*8)-.5)*.12,ripple*.06,_Wet),1));
            o.Smoothness=lerp(.07,.78,_Wet);
            o.Metallic=_Wet*.12;
            o.Occlusion=.85+soil*.22;
        }
        ENDCG
    }
    Fallback "Diffuse"
}
