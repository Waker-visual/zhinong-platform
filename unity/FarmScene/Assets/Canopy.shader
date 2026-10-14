Shader "Farm/Canopy" {
    Properties { _Color ("Canopy tint", Color) = (1,1,1,1) }
    SubShader {
        Tags { "RenderType"="TransparentCutout" "Queue"="AlphaTest" }
        Cull Off
        CGPROGRAM
        #pragma surface surf Standard vertex:vert addshadow fullforwardshadows
        #pragma target 3.0
        fixed4 _Color;
        struct Input { float3 worldPos; float4 color : COLOR; };
        float hash(float3 p) { return frac(sin(dot(p,float3(127.1,311.7,74.7)))*43758.5453); }
        float noise(float3 p) {
            float3 cell=floor(p),f=frac(p);f=f*f*(3-2*f);
            return lerp(lerp(lerp(hash(cell),hash(cell+float3(1,0,0)),f.x),lerp(hash(cell+float3(0,1,0)),hash(cell+float3(1,1,0)),f.x),f.y),lerp(lerp(hash(cell+float3(0,0,1)),hash(cell+float3(1,0,1)),f.x),lerp(hash(cell+float3(0,1,1)),hash(cell+1),f.x),f.y),f.z);
        }
        void vert(inout appdata_full v) {
            float leaf=step(v.color.r*1.12,v.color.g);
            v.vertex.x+=sin(v.vertex.z*.8+v.vertex.y*.6+_Time.y*.7)*.08*leaf;
        }
        void surf(Input i,inout SurfaceOutputStandard o) {
            float leaf=step(i.color.r*1.12,i.color.g);
            float clusters=noise(i.worldPos*5.5),detail=noise(i.worldPos*19);
            clip(lerp(1,clusters*.75+detail*.25-.24,leaf));
            o.Albedo=i.color.rgb*_Color.rgb*lerp(1,.6+clusters*.7+detail*.25,leaf);
            o.Normal=normalize(float3((detail-.5)*.55*leaf,(clusters-.5)*.45*leaf,1));
            o.Smoothness=.08;
            o.Occlusion=lerp(1,.68+clusters*.32,leaf);
        }
        ENDCG
    }
}
