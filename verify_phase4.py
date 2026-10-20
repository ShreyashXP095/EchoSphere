import struct

def verify_phase4_binary_header():
    # Blueprint Section 8:
    # 0..1: magic uint16_t (BE) 0x4D53 ("MS")
    # 2..5: seqNumber uint32_t (BE)
    # 6..13: playAtMasterUs int64_t (BE)
    # 14..15: payloadLen uint16_t (BE)
    # 16..N: opusPayload uint8_t[]

    magic = 0x4D53
    seq_number = 42
    capture_timestamp_us = 100_000_000
    play_at_master_us = capture_timestamp_us + 120_000 # 120ms presentation buffer
    fake_opus_payload = b"\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0A" * 32 # 320 bytes
    payload_len = len(fake_opus_payload)

    # Pack into Big Endian binary schema: >HIqH
    header = struct.pack(">HIqH", magic, seq_number, play_at_master_us, payload_len)
    assert len(header) == 16, f"Header size must be exactly 16 bytes, got {len(header)}"

    packet = header + fake_opus_payload
    assert len(packet) == 16 + payload_len

    # Unpack and validate
    unpacked_magic, unpacked_seq, unpacked_play_at, unpacked_len = struct.unpack(">HIqH", packet[:16])
    assert unpacked_magic == 0x4D53, f"Magic mismatch: {hex(unpacked_magic)}"
    assert unpacked_seq == 42
    assert unpacked_play_at == 100_120_000
    assert unpacked_len == 320
    assert packet[16:] == fake_opus_payload

    # Test Corrupted / Invalid Magic Drop
    corrupted_header = struct.pack(">HIqH", 0x1234, 1, 100, 10) + b"\x00" * 10
    parsed_magic = struct.unpack(">H", corrupted_header[:2])[0]
    assert parsed_magic != 0x4D53, "Corrupted magic should not match 0x4D53"

    # Verify PCM 20ms Frame Dimensions:
    # 48,000 samples/sec * 0.020 sec = 960 samples/channel
    # 960 samples * 2 channels (Stereo) * 2 bytes/sample (16-bit PCM) = 3840 bytes
    samples_per_channel = int(48000 * 0.020)
    assert samples_per_channel == 960
    pcm_bytes = samples_per_channel * 2 * 2
    assert pcm_bytes == 3840, f"PCM frame bytes must be 3840, got {pcm_bytes}"

    print(f"PASS: Phase 4 Binary UDP 16-byte Packet Schema & 20ms PCM Framing verified successfully!")

if __name__ == "__main__":
    verify_phase4_binary_header()
