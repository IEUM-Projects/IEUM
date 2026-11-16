package com.ieum.ieum.license;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 행정안전부 인허가 CSV 읽기 (DATA-02)
 * - 공공데이터포털에서 받은 파일은 보통 EUC-KR(CP949). UTF-8(BOM 포함)도 읽는다.
 * - zip으로 받은 파일은 압축을 풀지 않고 안의 첫 CSV를 읽는다.
 * - 따옴표 안의 쉼표·줄바꿈을 처리한다 ("경기도 ... 188, 1층 (득량동, ...)")
 */
public final class LicenseCsv implements AutoCloseable {

    /** 필요한 칸: 파일의 한글 칸 이름 또는 API 칸 이름 */
    static final Map<String, List<String>> COLUMNS = Map.of(
            "id", List.of("관리번호", "MNG_NO"),
            "name", List.of("사업장명", "BPLC_NM"),
            "road", List.of("도로명주소", "도로명전체주소", "ROAD_NM_ADDR"),
            "jibun", List.of("지번주소", "소재지전체주소", "LOTNO_ADDR"),
            "statusCode", List.of("영업상태코드", "영업상태구분코드", "SALS_STTS_CD"),
            "statusName", List.of("영업상태명", "SALS_STTS_NM"),
            "detailName", List.of("상세영업상태명", "DTL_SALS_STTS_NM"),
            "closed", List.of("폐업일자", "CLSBIZ_YMD"));

    private final ZipInputStream zip;
    private final BufferedReader reader;
    private final Map<String, Integer> index = new HashMap<>();
    private final List<String> header;

    public static class FormatException extends RuntimeException {
        public FormatException(String message) {
            super(message);
        }
    }

    public LicenseCsv(Path file) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        in.mark(4);
        byte[] magic = in.readNBytes(2);
        in.reset();
        ZipInputStream z = null;
        if (magic.length == 2 && magic[0] == 'P' && magic[1] == 'K') {
            z = new ZipInputStream(in, Charset.forName("MS949"));
            ZipEntry e;
            while ((e = z.getNextEntry()) != null && !e.getName().toLowerCase().endsWith(".csv")) {
                // csv가 나올 때까지 넘긴다
            }
            if (e == null) throw new FormatException("zip 안에 CSV 파일이 없어요.");
            in = new BufferedInputStream(z, 1 << 16);
        }
        this.zip = z;
        Charset cs = detect(in);
        this.reader = new BufferedReader(new InputStreamReader(in, cs), 1 << 16);
        List<String> h = next();
        if (h == null) throw new FormatException("빈 파일이에요.");
        if (!h.isEmpty()) h.set(0, h.get(0).replace("﻿", ""));
        this.header = h;
        for (Map.Entry<String, List<String>> c : COLUMNS.entrySet()) {
            for (String name : c.getValue()) {
                int i = h.indexOf(name);
                if (i >= 0) {
                    index.put(c.getKey(), i);
                    break;
                }
            }
        }
        List<String> missing = new ArrayList<>();
        if (!index.containsKey("id")) missing.add("관리번호");
        if (!index.containsKey("name")) missing.add("사업장명");
        if (!index.containsKey("road") && !index.containsKey("jibun")) missing.add("도로명주소 또는 지번주소");
        if (!index.containsKey("statusCode") && !index.containsKey("statusName")) missing.add("영업상태명");
        if (!missing.isEmpty()) {
            String found = String.join(", ", h.subList(0, Math.min(h.size(), 15)));
            throw new FormatException("필요한 칸(" + String.join(", ", missing) + ")을 찾지 못했어요. 파일의 칸: " + found);
        }
    }

    /** 앞부분을 UTF-8로 엄격하게 읽어 보고, 깨지면 CP949 */
    private static Charset detect(InputStream in) throws IOException {
        in.mark(1 << 16);
        byte[] head = in.readNBytes(1 << 16);
        in.reset();
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        int len = head.length;
        while (len > 0 && len > head.length - 4 && (head[len - 1] & 0xC0) == 0x80) len--;   // 끝에서 잘린 글자는 빼고
        if (len > 0 && (head[len - 1] & 0x80) != 0) len--;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(head, 0, Math.max(len, 0)));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return Charset.forName("MS949");
        }
    }

    public List<String> header() {
        return header;
    }

    /** 다음 줄을 칸 목록으로 (파일 끝이면 null) */
    public List<String> next() throws IOException {
        String line = reader.readLine();
        if (line == null) return null;
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        while (true) {
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    out.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            if (!quoted) break;
            String more = reader.readLine();   // 따옴표 안에서 줄이 바뀐 경우
            if (more == null) break;
            cell.append(' ');
            line = more;
        }
        out.add(cell.toString());
        return out;
    }

    /** 칸 값 (없거나 비어 있으면 null) */
    public String get(List<String> row, String column) {
        Integer i = index.get(column);
        if (i == null || i >= row.size()) return null;
        String v = row.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    @Override
    public void close() throws IOException {
        reader.close();
        if (zip != null) zip.close();
    }

}
