"""Generate small synthetic DASH/WebM and split-audio HLS fixtures with FFmpeg."""
import argparse
from pathlib import Path
import subprocess


def generate(root):
    root.mkdir(parents=True, exist_ok=True)
    dash = root / "dash"
    hls = root / "hls"
    dash.mkdir(exist_ok=True)
    hls.mkdir(exist_ok=True)

    def ffmpeg(args, directory):
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], cwd=directory, check=True, timeout=120)

    ffmpeg([
        "-f", "lavfi", "-i", "color=c=navy:s=3840x2160:r=2:d=2",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
        "-f", "lavfi", "-i", "sine=frequency=880:duration=2",
        "-filter_complex", "[0:v]split=3[v4][vf][vs];[vf]scale=1920:1080,setsar=1[vhd];[vs]scale=854:480,setsar=1[vsd]",
        "-map", "[v4]", "-map", "[vhd]", "-map", "[vsd]", "-map", "1:a", "-map", "2:a",
        "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-crf", "40", "-b:v", "0", "-threads", "2",
        "-c:a", "libopus", "-b:a", "64k", "-f", "dash", "-seg_duration", "1",
        "-use_template", "1", "-use_timeline", "1", "-adaptation_sets", "id=0,streams=0 id=1,streams=1 id=2,streams=2 id=3,streams=3 id=4,streams=4",
        "-init_seg_name", "init-$RepresentationID$.webm", "-media_seg_name", "chunk-$RepresentationID$-$Number%05d$.webm", "manifest.mpd",
    ], dash)
    ffmpeg(["-f", "lavfi", "-i", "color=c=navy:s=1920x1080:r=2:d=2", "-an", "-c:v", "libx264", "-preset", "ultrafast",
            "-pix_fmt", "yuv420p", "-g", "2", "-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod",
            "-hls_segment_filename", "video-%d.ts", "video.m3u8"], hls)
    for label, frequency in [("a", 440), ("b", 880)]:
        ffmpeg(["-f", "lavfi", "-i", f"sine=frequency={frequency}:duration=2", "-vn", "-c:a", "aac", "-b:a", "64k",
                "-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod", "-hls_segment_filename", f"audio-{label}-%d.ts", f"audio-{label}.m3u8"], hls)
    (hls / "master.m3u8").write_text('''#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Studio A",DEFAULT=YES,AUTOSELECT=YES,URI="audio-a.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Studio B",DEFAULT=NO,AUTOSELECT=YES,URI="audio-b.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=1920x1080,CODECS="avc1.42c028,mp4a.40.2",AUDIO="audio"
video.m3u8
''', encoding="utf-8")
    torrent = root / "torrent"
    torrent.mkdir(exist_ok=True)
    ffmpeg(["-i", str((dash / "manifest.mpd").resolve()), "-map", "0:v:0", "-map", "0:a:0", "-map", "0:a:1",
            "-c", "copy", "-metadata:s:a:0", "title=Studio A", "-metadata:s:a:1", "title=Studio B",
            "-metadata:s:a:0", "language=rus", "-metadata:s:a:1", "language=eng", "selected.webm"], torrent)
    print("Generated", sum(p.stat().st_size for p in root.rglob("*") if p.is_file()), "bytes of synthetic test media")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    generate(parser.parse_args().destination)
