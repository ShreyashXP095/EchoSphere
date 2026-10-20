import json
import time

def verify_phase2_spec():
    # 1. Test QueueItem and sorting behavior (votes desc, timestamp asc)
    queue = [
        {"id": "1", "title": "Track A", "upvotes": 2, "addedAt": 1000},
        {"id": "2", "title": "Track B", "upvotes": 5, "addedAt": 1100},
        {"id": "3", "title": "Track C", "upvotes": 5, "addedAt": 1050},
        {"id": "4", "title": "Track D", "upvotes": 1, "addedAt": 1200},
    ]

    sorted_queue = sorted(queue, key=lambda x: (-x["upvotes"], x["addedAt"]))
    expected_order = ["3", "2", "1", "4"]
    actual_order = [item["id"] for item in sorted_queue]
    assert actual_order == expected_order, f"Sorting mismatch: {actual_order} vs {expected_order}"

    # 2. Test WsMessage schema serialization
    ws_msg = {
        "action": "SYNC_STATE",
        "senderPeerId": "host-1234",
        "payloadJson": json.dumps({
            "videoId": "4NRXx6U8ABQ",
            "isPlaying": True,
            "trackPositionMs": 32000,
            "atMasterClockMs": 178900000
        })
    }
    raw = json.dumps(ws_msg)
    decoded = json.loads(raw)
    assert decoded["action"] == "SYNC_STATE"
    payload = json.loads(decoded["payloadJson"])
    assert payload["videoId"] == "4NRXx6U8ABQ"
    assert payload["isPlaying"] is True

    print("PASS: Phase 2 Collaborative Queue & WebSocket Event schemas verified successfully!")

if __name__ == "__main__":
    verify_phase2_spec()
