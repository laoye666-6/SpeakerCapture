import de.sciss.jump3r.mp3.BitStream;
import de.sciss.jump3r.mp3.GainAnalysis;
import de.sciss.jump3r.mp3.ID3Tag;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import de.sciss.jump3r.mp3.MPEGMode;
import de.sciss.jump3r.mp3.Presets;
import de.sciss.jump3r.mp3.Quantize;
import de.sciss.jump3r.mp3.QuantizePVT;
import de.sciss.jump3r.mp3.Reservoir;
import de.sciss.jump3r.mp3.Takehiro;
import de.sciss.jump3r.mp3.VBRTag;
import de.sciss.jump3r.mp3.Version;
import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;

import java.io.FileInputStream;
import java.io.FileOutputStream;

/** 在桌面 JVM 上验证 CaptureService.createLame() 的装配与编码逻辑(纯 Java,无 Android 依赖)。 */
public class Mp3EncodeTest {
    static final boolean SCALE_SHIFT = System.getProperty("scale16") == null;
    static final int SCALE = SCALE_SHIFT ? 16 : 0;

    public static void main(String[] args) throws Exception {
        int sampleRate = 44100, channels = 2, bitrate = 192;
        byte[] pcm;
        try (FileInputStream in = new FileInputStream(args[0])) {
            byte[] all = in.readAllBytes();
            pcm = new byte[all.length - 44];          // 跳过 WAV 头
            System.arraycopy(all, 44, pcm, 0, pcm.length);
        }

        Lame lame = createLame();
        LameGlobalFlags gfp = lame.lame_init();
        // C 前端 main.c 也会调用 id3tag_init:初始化 tag_spec,必须在 init_params 之前
        id3Module.id3tag_init(gfp);
        gfp.in_samplerate = sampleRate;
        gfp.num_channels = channels;
        gfp.brate = bitrate;
        gfp.quality = 2;
        gfp.mode = MPEGMode.JOINT_STEREO;
        lame.lame_init_params(gfp);

        byte[] mp3buf = new byte[Lame.LAME_MAXMP3BUFFER];
        int[] left = new int[4096], right = new int[4096];
        java.io.ByteArrayOutputStream mp3 = new java.io.ByteArrayOutputStream();
        int framesTotal = pcm.length / 4;
        for (int off = 0; off < framesTotal; off += 2048) {
            int frames = Math.min(2048, framesTotal - off);
            for (int i = 0; i < frames; i++) {
                int b = (off + i) * 4;
                int l16 = (short) ((pcm[b] & 0xFF) | (pcm[b + 1] << 8));
                int r16 = (short) ((pcm[b + 2] & 0xFF) | (pcm[b + 3] << 8));
                // 适配编码器的 int 采样约定:1.0 对应 2^31(16bit 值左移 16 位)
                left[i] = SCALE_SHIFT ? l16 << SCALE : l16;
                right[i] = SCALE_SHIFT ? r16 << SCALE : r16;
            }
            int got = lame.lame_encode_buffer_int(gfp, left, right, frames, mp3buf, 0, mp3buf.length);
            if (got > 0) mp3.write(mp3buf, 0, got);
            else if (got < 0) throw new RuntimeException("encode error " + got);
        }
        int flushed = lame.lame_encode_flush(gfp, mp3buf, 0, mp3buf.length);
        if (flushed > 0) mp3.write(mp3buf, 0, flushed);
        lame.lame_close(gfp);

        try (FileOutputStream out = new FileOutputStream(args[1])) {
            out.write(mp3.toByteArray());
        }
        System.out.println("PCM " + pcm.length + " bytes -> MP3 " + mp3.size() + " bytes");
    }

    private static ID3Tag id3Module;

    // 与 CaptureService.createEncoder() 完全一致
    private static Lame createLame() {
        Lame lame = new Lame();
        GainAnalysis ga = new GainAnalysis();
        BitStream bs = new BitStream();
        Presets p = new Presets();
        QuantizePVT qupvt = new QuantizePVT();
        Quantize qu = new Quantize();
        VBRTag vbr = new VBRTag();
        Version ver = new Version();
        ID3Tag id3 = id3Module = new ID3Tag();
        Reservoir rv = new Reservoir();
        Takehiro tak = new Takehiro();
        MPGLib mpg = new MPGLib();
        Interface intf = new Interface();
        Common common = new Common();

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg);
        bs.setModules(ga, mpg, ver, vbr);
        id3.setModules(bs, ver);
        p.setModules(lame);
        qu.setModules(bs, rv, qupvt, tak);
        qupvt.setModules(tak, rv, lame.enc.psy);
        rv.setModules(bs);
        tak.setModules(qupvt);
        vbr.setModules(lame, bs, ver);
        mpg.setModules(intf, common);
        intf.setModules(vbr, common);
        return lame;
    }
}
