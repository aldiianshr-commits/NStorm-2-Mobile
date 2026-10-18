package com.nstorm2.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.nstorm2.engine.scene.SceneModel;
import com.nstorm2.engine.texture.NutTexture;
import com.nstorm2.engine.xfbin.NuccChunks;
import com.nstorm2.engine.xfbin.Xfbin;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * synthetic_clump.xfbin was written by xfbin_lib's own XFBIN writer (see
 * make_synthetic_clump.py): 2 bones (child rotated 90 deg around Y), one skinned
 * quad bound to the child bone, a material and an 8x4 DXT1 texture.
 */
public class XfbinFixtureTest {

    static byte[] resource(String name) throws Exception {
        try (InputStream in = XfbinFixtureTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Test
    public void parsesPagesAndClump() throws Exception {
        byte[] bytes = resource("synthetic_clump.xfbin");
        assertTrue(Xfbin.declaresType(bytes, NuccChunks.CLUMP));
        Xfbin x = Xfbin.parse(bytes);
        assertEquals(2, x.pages().size());
        assertEquals(1, SceneModel.clumps(x).size());
    }

    @Test
    public void buildsBindPoseModel() throws Exception {
        Xfbin x = Xfbin.parse(resource("synthetic_clump.xfbin"));
        SceneModel s = SceneModel.build(x, SceneModel.clumps(x).get(0));
        assertTrue(s.warnings.toString(), s.warnings.isEmpty());
        assertEquals(2, s.bones.size());
        assertEquals(0, s.bones.get(1).parent);
        assertEquals(1, s.parts.size());
        SceneModel.Part p = s.parts.get(0);
        assertEquals(2, p.triangleCount());
        float[] v = p.vertices;
        int o = SceneModel.STRIDE; // vertex 1 = (1,0,0) in child-bone space
        assertEquals(10f, v[o], 1e-3f);
        assertEquals(100f, v[o + 1], 1e-3f);
        assertEquals(-1f, v[o + 2], 1e-3f);
        assertEquals(1f, v[o + 3], 1e-3f);   // normal (0,0,1) rotated to (1,0,0)
        assertEquals(1f, v[o + 6], 1e-3f);   // u
        assertEquals(0f, v[o + 7], 1e-3f);   // v
        assertNotNull(p.texture);
        assertEquals("test_tex", p.texture.name);
    }

    @Test
    public void decodesDxt1Texture() throws Exception {
        Xfbin x = Xfbin.parse(resource("synthetic_clump.xfbin"));
        Xfbin.Chunk tex = x.chunksOfType(NuccChunks.TEXTURE).get(0);
        List<NutTexture> nut = NutTexture.parse(NuccChunks.textureNut(tex));
        assertEquals(1, nut.size());
        NutTexture t = nut.get(0);
        assertEquals(8, t.width);
        assertEquals(4, t.height);
        assertEquals("DXT1", t.formatName());
        int[] px = t.decodeArgb();
        assertEquals(0xFFFF0000, px[0]);
        assertEquals(0xFFFF0000, px[3 * 8 + 3]);
        assertEquals(0xFF000000, px[4]);
        assertEquals(0xFF000000, px[3 * 8 + 7]);
    }
}
