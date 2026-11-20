from conftest import HEIGHT, WIDTH, blank_frame, frame_with_qr

from worker.decoder import find_qr_codes

LINK = "https://business.payaza.africa/pay/nsppd"


def test_reads_a_giving_qr_code_on_a_slide():
    assert find_qr_codes(frame_with_qr(LINK), WIDTH, HEIGHT) == [LINK]


def test_reads_a_small_code_in_a_corner():
    assert find_qr_codes(frame_with_qr(LINK, size=110, x=830, y=410), WIDTH, HEIGHT) == [LINK]


def test_an_ordinary_frame_has_no_codes():
    assert find_qr_codes(blank_frame(), WIDTH, HEIGHT) == []


def test_a_frame_of_the_wrong_size_is_ignored_not_an_error():
    assert find_qr_codes(b"\x00" * 10, WIDTH, HEIGHT) == []
