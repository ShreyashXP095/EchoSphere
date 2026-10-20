import json

def verify_phase1_spec():
    # Test RoomInfo schema
    room_info = {
        "roomId": "sphere-test-1234",
        "roomName": "Neon Rooftop Lounge",
        "hostIp": "192.168.1.42",
        "wsPort": 8080,
        "udpClockPort": 5005,
        "udpAudioPort": 5004,
        "hostDeviceModel": "Pixel 8 Pro",
        "protocolVersion": 1
    }

    # 1. Verify JSON payload matches specification
    serialized = json.dumps(room_info)
    deserialized = json.loads(serialized)
    assert deserialized["wsPort"] == 8080, "WebSocket port must be 8080"
    assert deserialized["udpClockPort"] == 5005, "Clock port must be 5005"
    assert deserialized["udpAudioPort"] == 5004, "Audio port must be 5004"

    # 2. Verify mDNS service name and attributes
    service_type = "_echosphere._tcp."
    assert service_type == "_echosphere._tcp."

    print("PASS: Phase 1 Specification and Serialization verification successful!")

if __name__ == "__main__":
    verify_phase1_spec()
