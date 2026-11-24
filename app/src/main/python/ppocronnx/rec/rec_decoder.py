"""CTC 贪心解码（对齐 ppocr-onnx / PaddleOCR 的 CTCLabelDecode 语义）。

与上游的区别：上游接收 [T, C] 的完整概率矩阵再 argmax；本仓的 argmax 在
Kotlin 侧完成（避免每次跨 Python/Java 边界搬 ~2MB），这里只接收索引与分数。

字典映射（与 PaddleOCR 一致）：
  index 0            -> blank（CTC 占位，不输出）
  index 1..len(dict) -> character[index-1]
  index len+1        -> 空格（use_space_char=True 时）
"""
from typing import List, Sequence, Tuple

__all__ = ["decode_argmax"]


def decode_argmax(
    indices: Sequence[float],
    scores: Sequence[float],
    character: List[str],
    use_space_char: bool = True,
    ignore_space: bool = False,
    whitelist: Sequence[str] = None,
) -> Tuple[str, float]:
    """把每步的 argmax 索引解码成文本。

    :param indices: 每个时间步的 argmax 索引
    :param scores: 对应概率
    :param character: 字典（不含 blank）
    :param ignore_space: 是否丢弃空格
    :param whitelist: 只保留这些字符（None 表示不过滤）
    :return: (text, mean_score)
    """
    n = len(character)
    out_chars: List[str] = []
    out_scores: List[float] = []
    prev = -1
    for i_raw, s in zip(indices, scores):
        i = int(i_raw)
        if i == prev:          # CTC 折叠连续重复
            continue
        prev = i
        if i == 0:             # blank
            continue
        if 1 <= i <= n:
            ch = character[i - 1]
        elif i == n + 1 and use_space_char:
            ch = " "
        else:
            continue
        if ignore_space and ch == " ":
            continue
        if whitelist is not None and ch not in whitelist:
            continue
        out_chars.append(ch)
        out_scores.append(float(s))

    text = "".join(out_chars)
    score = (sum(out_scores) / len(out_scores)) if out_scores else 0.0
    return text, score