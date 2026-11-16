package de.sciss.jump3r.mp3;

import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Android-safe bridge to jump3r's pure-Java LAME MP3 encoder without referencing javax.sound.sampled.
 */
public final class Jump3rBridge implements Closeable {

    private final Lame lame;
    private final LameGlobalFlags gfp;
    private final ID3Tag id3;
    private final OutputStream outputStream;
    private final int channels;
    private byte[] mp3Buffer = new byte[16384];
    private boolean closed = false;

    public Jump3rBridge(
        OutputStream outputStream,
        int sampleRate,
        int channels,
        int bitrateKbps,
        String title,
        String artist,
        String album,
        int trackNumber
    ) throws IOException {
        this.outputStream = outputStream;
        this.channels = Math.max(1, Math.min(2, channels));

        this.lame = new Lame();
        GainAnalysis ga = new GainAnalysis();
        BitStream bs = new BitStream();
        Presets p = new Presets();
        QuantizePVT qupvt = new QuantizePVT();
        Quantize qu = new Quantize();
        VBRTag vbr = new VBRTag();
        Version ver = new Version();
        this.id3 = new ID3Tag();
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
        qupvt.setModules(tak, rv, lame.psy);
        rv.setModules(bs);
        tak.setModules(qupvt);
        vbr.setModules(lame, bs, ver);
        mpg.setModules(intf, common);
        intf.setModules(vbr, common);

        this.gfp = lame.lame_init();
        gfp.in_samplerate = sampleRate;
        gfp.out_samplerate = sampleRate;
        gfp.num_channels = this.channels;
        gfp.mode = (this.channels == 1) ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
        gfp.brate = bitrateKbps;
        gfp.quality = 5;
        gfp.VBR = VbrMode.vbr_off;
        gfp.write_id3tag_automatic = true;

        id3.id3tag_init(gfp);
        id3.id3tag_v2_only(gfp);
        if (title != null && !title.isEmpty()) id3.id3tag_set_title(gfp, title);
        if (artist != null && !artist.isEmpty()) id3.id3tag_set_artist(gfp, artist);
        if (album != null && !album.isEmpty()) id3.id3tag_set_album(gfp, album);
        if (trackNumber > 0) id3.id3tag_set_track(gfp, String.valueOf(trackNumber));

        int rc = lame.lame_init_params(gfp);
        if (rc < 0) {
            throw new IOException("LAME parameter initialization failed: " + rc);
        }
    }

    /**
     * Encodes 16-bit interleaved PCM samples into MP3 frames.
     */
    public void encodePcm16(short[] interleavedSamples, int sampleCount) throws IOException {
        if (closed || sampleCount <= 0) return;
        int frames = sampleCount / channels;
        if (frames <= 0) return;

        int[] left = new int[frames];
        int[] right = new int[frames];
        if (channels == 1) {
            for (int i = 0; i < frames; i++) {
                int s = ((int) interleavedSamples[i]) << 16;
                left[i] = s;
                right[i] = s;
            }
        } else {
            for (int i = 0; i < frames; i++) {
                left[i] = ((int) interleavedSamples[i * 2]) << 16;
                right[i] = ((int) interleavedSamples[i * 2 + 1]) << 16;
            }
        }

        int needed = (int) (1.25 * frames + 7200);
        if (mp3Buffer.length < needed) {
            mp3Buffer = new byte[needed];
        }

        int encoded = lame.lame_encode_buffer_int(gfp, left, right, frames, mp3Buffer, 0, mp3Buffer.length);
        if (encoded > 0) {
            outputStream.write(mp3Buffer, 0, encoded);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            int flushed = lame.lame_encode_flush(gfp, mp3Buffer, 0, mp3Buffer.length);
            if (flushed > 0) {
                outputStream.write(mp3Buffer, 0, flushed);
            }
            lame.lame_close(gfp);
        } finally {
            outputStream.flush();
            outputStream.close();
        }
    }
}
