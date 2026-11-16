Shader "Farm/LandCover" {
    // Procedural farmland painted from a land-cover map (tools/scene/classify_landcover.py).
    // The satellite photograph is only a layout guide: fields, water, bunds and roads keep their
    // positions, but every surface is drawn as crop rows, soil, water or gravel at metre scale.
    Properties {
        _Cover ("Land cover: R water, G maturity, B road, A bund", 2D) = "black" {}
        _Flow ("Row direction (cos 2a, sin 2a)", 2D) = "gray" {}
        _WorldRect ("West, south, width, depth", Vector) = (0,0,1,1)
        _RowSpacing ("Crop row spacing (m)", Float) = 0.9
        _SkyTint ("Reflected sky", Color) = (.56,.69,.80,1)
    }
    SubShader {
        Tags { "RenderType"="Opaque" }
        CGPROGRAM
        #pragma surface surf Standard fullforwardshadows vertex:vert
        #pragma target 3.0
        sampler2D _Cover, _Flow;
        float4 _WorldRect;
        float _RowSpacing, _SeasonShift;
        fixed4 _SkyTint;
        struct Input { float3 worldPos; float3 viewDir; };

        // Flat ground: a fixed world-aligned tangent frame lets the shader bend normals for rows.
        void vert(inout appdata_full v) { v.normal = float3(0,1,0); v.tangent = float4(1,0,0,1); }

        float hash(float2 p) { return frac(sin(dot(p,float2(127.1,311.7)))*43758.5453); }
        float noise(float2 p) {
            float2 i=floor(p),f=frac(p);f=f*f*(3-2*f);
            return lerp(lerp(hash(i),hash(i+float2(1,0)),f.x),lerp(hash(i+float2(0,1)),hash(i+1),f.x),f.y);
        }
        // Raised crop ridge profile across one row: 1 on the plant line, 0 in the furrow.
        float ridge(float r, float density) { float w=abs(frac(r)-.5)*2; return smoothstep(1-density-.18,1-density+.18,1-w); }

        void surf(Input IN, inout SurfaceOutputStandard o) {
            float2 p=IN.worldPos.xz;
            float2 uv=(p-_WorldRect.xy)/_WorldRect.zw;
            float4 cover=tex2D(_Cover,uv);
            float2 flow=tex2D(_Flow,uv).rg*2-1;
            float angle=atan2(flow.y,flow.x)*.5;
            float2 along=float2(cos(angle),sin(angle)), across=float2(-along.y,along.x);
            float dist=distance(_WorldSpaceCameraPos,IN.worldPos);
            float near=1-smoothstep(40,380,dist);

            // Field-scale variation so neighbouring fields of the same stage still differ.
            float macro=noise(p*.011)*.6+noise(p*.043)*.4;
            float maturity=saturate(cover.g+_SeasonShift+(macro-.5)*.14);
            float water=smoothstep(.38,.62,cover.r);
            float road=smoothstep(.3,.7,cover.b);
            float bund=smoothstep(.3,.75,cover.a)*(1-road);

            // Crop rows, antialiased: once a row is smaller than ~2 pixels it fades to its average.
            float rowCoord=dot(p,across)/_RowSpacing;
            // Fade rows well before they reach pixel size, or distant fields shimmer with moire.
            float aa=saturate(fwidth(rowCoord)*2.6-.3);
            float density=lerp(.78,.42,maturity);
            float rows=lerp(ridge(rowCoord,density),density,aa);
            float plant=noise(float2(rowCoord*2.2,dot(p,along)*2.6));
            float leaf=lerp(.5,plant,near*(1-aa));

            // Crop palette in linear space: lush green -> heading yellow-green -> golden stubble.
            float3 young=float3(.055,.17,.035), lush=float3(.085,.235,.045), heading=float3(.30,.29,.075), stubble=float3(.52,.40,.16);
            float3 crop=maturity<.45?lerp(young,lush,maturity/.45):maturity<.75?lerp(lush,heading,(maturity-.45)/.3):lerp(heading,stubble,(maturity-.75)/.25);
            crop*=.8+leaf*.4;
            // Stubble: short straw streaks along the rows over pale, cut soil.
            float straw=noise(float2(rowCoord*5,dot(p,along)*.9))*noise(float2(rowCoord*11,dot(p,along)*3.7));
            crop=lerp(crop,crop*(.75+straw*.7),smoothstep(.6,.9,maturity)*near*(1-aa));
            float3 soil=lerp(float3(.075,.058,.038),float3(.25,.19,.12),maturity)*(.85+noise(p*3.1)*.3*near);
            // Each field gets its own light/dark cast, like real neighbouring plots.
            float3 field=lerp(soil,crop,rows)*(.86+macro*.28);

            // Flooded paddies: dark water with faint seedling rows where the field is planted.
            float seedlings=step(.42,macro)*ridge(rowCoord*1.15,.22)*(1-aa)*.55;
            float3 paddy=lerp(float3(.03,.065,.07),young*1.2,seedlings);

            // Verges: patchy grass with drier tufts, so wide bunds never read as flat paint.
            float tuft=noise(p*.9)*.6+noise(p*4.3)*.4;
            float3 grass=lerp(float3(.06,.15,.035),float3(.17,.19,.07),smoothstep(.45,.85,tuft))*(.82+noise(p*13)*.3*near);
            float3 gravel=float3(.40,.37,.31)*(.88+noise(p*4.3)*.24*near);

            float3 albedo=lerp(field,paddy,water);
            albedo=lerp(albedo,grass,bund);
            albedo=lerp(albedo,gravel,road);
            o.Albedo=albedo;

            // Row relief: bend the normal across rows, flatter where water, roads or distance win.
            float e=.08;
            float slope=(ridge(rowCoord+e,density)-ridge(rowCoord-e,density))/(2*e*_RowSpacing);
            float relief=.16*(1-aa)*(1-water)*(1-road)*(1-bund)*near;
            float2 grad=across*slope*relief;
            o.Normal=normalize(float3(-grad.x,grad.y,1));

            float fresnel=pow(1-saturate(normalize(IN.viewDir).z),3);
            float ripple=noise(p*.6+_Time.y*.08)*.5+.5;
            o.Emission=_SkyTint.rgb*water*(1-seedlings)*(.04+.3*fresnel)*ripple;
            o.Smoothness=lerp(lerp(.1,.22,1-maturity)*(1-road)+.06*road,.9,water*(1-seedlings*.6));
            o.Occlusion=lerp(1,.75+.25*rows,(1-water)*(1-road));
        }
        ENDCG
    }
    FallBack "Diffuse"
}
