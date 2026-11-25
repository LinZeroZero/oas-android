"""识别（rec）子包。"""
from .predict_rec import TextRecognizer
from .rec_decoder import decode_argmax

__all__ = ["TextRecognizer", "decode_argmax"]