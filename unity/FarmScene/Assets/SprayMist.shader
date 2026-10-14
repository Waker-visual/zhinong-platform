Shader "Farm/SprayMist" {
    Properties { _Color ("Mist tint", Color) = (.83,.92,1,.085) }
    SubShader {
        Tags { "Queue"="Transparent" "RenderType"="Transparent" }
        ZWrite Off
        CGPROGRAM
        #pragma surface surf Standard alpha:fade noshadow
        fixed4 _Color;
        struct Input { float3 viewDir; };
        void surf(Input i,inout SurfaceOutputStandard o) {
            o.Albedo=0;
            o.Emission=_Color.rgb;
            o.Alpha=_Color.a*pow(saturate(abs(normalize(i.viewDir).z)),4);
            o.Smoothness=0;
        }
        ENDCG
    }
}
