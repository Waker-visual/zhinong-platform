Shader "Farm/OrthoGround" {
    Properties {
        _MainTex ("Registered satellite imagery", 2D) = "white" {}
        _WorldRect ("West, south, width, depth", Vector) = (0,0,1,1)
        _Color ("Tint", Color) = (1,1,1,1)
        _Glossiness ("Smoothness", Range(0,1)) = 0.12
        _Saturation ("Saturation", Range(0,2)) = 1.16
        _ShadowLift ("Shadow lift", Range(0,1)) = 0.3
        _Grain ("Close-range ground grain", Range(0,.3)) = 0.12
        _WetSheen ("Sheen on dark flooded tones", Range(0,1)) = 0.55
        _SkyTint ("Reflected sky", Color) = (.56,.69,.80,1)
    }
    SubShader {
        Tags { "RenderType"="Opaque" }
        CGPROGRAM
        #pragma surface surf Standard fullforwardshadows
        #pragma target 3.0
        sampler2D _MainTex;
        float4 _WorldRect;
        fixed4 _Color;
        half _Glossiness, _Saturation, _ShadowLift, _Grain, _WetSheen;
        fixed4 _SkyTint;
        struct Input { float3 worldPos; float3 viewDir; };
        float hash(float2 p) { return frac(sin(dot(p,float2(127.1,311.7)))*43758.5453); }
        float noise(float2 p) {
            float2 i=floor(p),f=frac(p);f=f*f*(3-2*f);
            return lerp(lerp(hash(i),hash(i+float2(1,0)),f.x),lerp(hash(i+float2(0,1)),hash(i+1),f.x),f.y);
        }
        void surf(Input IN, inout SurfaceOutputStandard o) {
            float2 uv=(IN.worldPos.xz-_WorldRect.xy)/_WorldRect.zw;
            fixed3 c=tex2D(_MainTex,uv).rgb;
            const float3 lum=float3(.2126,.7152,.0722);
            float original=dot(c,lum);
            // The photograph already includes shadows. Lift the deepest tones instead of
            // multiplying those baked shadows by a second fully dark lighting pass.
            c=lerp(c,sqrt(max(c,0)),_ShadowLift);
            float l=dot(c,lum);
            c=max(0,lerp(l.xxx,c,_Saturation));
            // Gentle split tone: cool, clear shadows and warm sunlit stubble.
            c*=lerp(float3(.95,1.0,1.05),float3(1.05,1.0,.92),smoothstep(.12,.55,l));
            // Imagery is ~0.3 m per pixel; fine grain hides magnification blur near the camera
            // without inventing features (it is a uniform tonal variation, not a pattern).
            float2 p=IN.worldPos.xz;
            float near=1-smoothstep(35,220,distance(_WorldSpaceCameraPos,IN.worldPos));
            float grain=noise(p*1.9)*.45+noise(p*7.3)*.35+noise(p*23)*.2*near;
            float amount=_Grain*(1+near*1.6);
            c*=1-amount*.5+grain*amount;
            // Very dark pixels are usually standing water in paddies: give them a soft,
            // view-dependent sky reflection. Purely a shading cue; no data is derived from it.
            float wet=(1-smoothstep(.04,.13,original))*_WetSheen;
            float fresnel=pow(1-saturate(normalize(IN.viewDir).z),3);
            float ripple=noise(p*.35+_Time.y*.05)*.5+.5;
            o.Albedo=c*_Color.rgb*(1-wet*.25);
            o.Emission=c*_Color.rgb*.06+_SkyTint.rgb*wet*(.05+.32*fresnel)*ripple;
            o.Smoothness=lerp(_Glossiness,.82,wet);
            o.Metallic=0;
            o.Occlusion=1;
        }
        ENDCG
    }
    FallBack "Diffuse"
}
