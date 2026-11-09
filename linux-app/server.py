import os
import sys
import io
import json
import base64
import numpy as np
from PIL import Image
import onnxruntime as ort
from http.server import HTTPServer, SimpleHTTPRequestHandler

PORT = 8089
MODEL_PATH = "germed_mobile_v4_int8.onnx"

print(f"Loading GerMed INT8 ONNX Engine from: {MODEL_PATH}...")
session = ort.InferenceSession(MODEL_PATH)

def preprocess_image(image_bytes):
    img = Image.open(io.BytesIO(image_bytes)).convert("RGB").resize((256, 256))
    arr = np.array(img).astype(np.float32) / 255.0
    mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
    tensor = np.expand_dims(np.transpose((arr - mean) / std, (2, 0, 1)), axis=0)
    return tensor

def run_analysis(image_bytes):
    tensor = preprocess_image(image_bytes)
    outputs = session.run(None, {"input_image": tensor})
    risk = float(outputs[0][0][0])
    asym = float(outputs[1][0][0])
    border = float(outputs[2][0][0])
    color = float(outputs[3][0][0])
    pattern = float(outputs[4][0][0])
    
    risk_pct = round(risk * 100)
    
    if risk >= 0.65:
        risk_level = "High risk"
        risk_color = "#E53935"
        bar_color = "#EF5350"
        what_this_means = "The result indicates an elevated likelihood of malignancy. Prompt dermatological examination and potential biopsy are strongly advised."
        recommendation = "Contact a dermatologist or skin cancer specialist immediately for an in-person dermoscopic evaluation."
    elif risk >= 0.40:
        risk_level = "Moderate risk"
        risk_color = "#FB8C00"
        bar_color = "#FFA726"
        what_this_means = "The result shows suspicious features. While not conclusively malignant, close inspection by a physician is recommended."
        recommendation = "Schedule a routine check-up with a dermatologist and avoid irritating or exposing the lesion to intense UV light."
    else:
        risk_level = "Low risk"
        risk_color = "#2E7D32"
        bar_color = "#4CAF50"
        what_this_means = "The result suggests a low likelihood of skin cancer. Many benign moles can look similar, and changes over time are important."
        recommendation = "If the mole changes in size, shape, color, or starts to bleed or itch, consult a dermatologist."

    return {
        "risk_percentage": risk_pct,
        "risk_level": risk_level,
        "risk_color": risk_color,
        "bar_color": bar_color,
        "summary": f"The model estimates a {risk_pct}% probability that the analyzed lesion is a malignant skin tumor (e.g. melanoma).",
        "what_this_means": what_this_means,
        "recommendation": recommendation,
        "thinking_mode": {
            "asymmetry": round(asym * 100, 1),
            "border": round(border * 100, 1),
            "color": round(color * 100, 1),
            "pattern": round(pattern * 100, 1)
        }
    }

class GerMedHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory="static", **kwargs)

    def do_POST(self):
        if self.path == "/api/analyze":
            content_length = int(self.headers.get("Content-Length", 0))
            post_data = self.rfile.read(content_length)
            try:
                body = json.loads(post_data.decode("utf-8"))
                img_b64 = body.get("image", "")
                if "," in img_b64:
                    img_b64 = img_b64.split(",")[1]
                image_bytes = base64.b64decode(img_b64)
                
                result = run_analysis(image_bytes)
                response = json.dumps(result).encode("utf-8")
                
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(response)))
                self.end_headers()
                self.wfile.write(response)
            except Exception as e:
                err_msg = json.dumps({"error": str(e)}).encode("utf-8")
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(err_msg)))
                self.end_headers()
                self.wfile.write(err_msg)
        else:
            self.send_response(404)
            self.end_headers()

def main():
    server_address = ("0.0.0.0", PORT)
    httpd = HTTPServer(server_address, GerMedHandler)
    print(f"GerMed Medical Web App running at http://localhost:{PORT}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server.")

if __name__ == "__main__":
    main()
