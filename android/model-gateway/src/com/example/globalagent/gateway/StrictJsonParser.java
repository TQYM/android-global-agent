package com.example.globalagent.gateway;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StrictJsonParser {
  private static final int MAX_DEPTH = 16;
  private static final int MAX_COLLECTION_ITEMS = 256;
  private static final int MAX_STRING_CHARS = 32 * 1024;

  private final String input;
  private int offset;

  private StrictJsonParser(String input) {
    this.input = input;
  }

  static Object parse(String input) {
    if (input == null) {
      throw invalid("null-input");
    }
    final StrictJsonParser parser = new StrictJsonParser(input);
    final Object value = parser.readValue(0);
    parser.skipWhitespace();
    if (parser.offset != input.length()) {
      throw invalid("trailing-data");
    }
    return value;
  }

  private Object readValue(int depth) {
    if (depth > MAX_DEPTH) {
      throw invalid("depth-limit");
    }
    skipWhitespace();
    if (offset >= input.length()) {
      throw invalid("unexpected-end");
    }
    switch (input.charAt(offset)) {
      case '{':
        return readObject(depth + 1);
      case '[':
        return readArray(depth + 1);
      case '"':
        return readString();
      case 't':
        readLiteral("true");
        return Boolean.TRUE;
      case 'f':
        readLiteral("false");
        return Boolean.FALSE;
      case 'n':
        readLiteral("null");
        return null;
      default:
        return readNumber();
    }
  }

  private Map<String, Object> readObject(int depth) {
    offset++;
    final Map<String, Object> object = new LinkedHashMap<>();
    skipWhitespace();
    if (consume('}')) {
      return object;
    }
    while (true) {
      skipWhitespace();
      if (offset >= input.length() || input.charAt(offset) != '"') {
        throw invalid("object-key");
      }
      final String key = readString();
      if (object.containsKey(key)) {
        throw invalid("duplicate-key");
      }
      skipWhitespace();
      require(':');
      object.put(key, readValue(depth));
      if (object.size() > MAX_COLLECTION_ITEMS) {
        throw invalid("object-size");
      }
      skipWhitespace();
      if (consume('}')) {
        return object;
      }
      require(',');
    }
  }

  private List<Object> readArray(int depth) {
    offset++;
    final List<Object> array = new ArrayList<>();
    skipWhitespace();
    if (consume(']')) {
      return array;
    }
    while (true) {
      array.add(readValue(depth));
      if (array.size() > MAX_COLLECTION_ITEMS) {
        throw invalid("array-size");
      }
      skipWhitespace();
      if (consume(']')) {
        return array;
      }
      require(',');
    }
  }

  private String readString() {
    require('"');
    final StringBuilder value = new StringBuilder();
    while (offset < input.length()) {
      final char character = input.charAt(offset++);
      if (character == '"') {
        if (value.length() > MAX_STRING_CHARS) {
          throw invalid("string-size");
        }
        return value.toString();
      }
      if (character < 0x20) {
        throw invalid("string-control");
      }
      if (character != '\\') {
        appendChecked(value, character);
        continue;
      }
      if (offset >= input.length()) {
        throw invalid("escape-end");
      }
      switch (input.charAt(offset++)) {
        case '"':
          appendChecked(value, '"');
          break;
        case '\\':
          appendChecked(value, '\\');
          break;
        case '/':
          appendChecked(value, '/');
          break;
        case 'b':
          appendChecked(value, '\b');
          break;
        case 'f':
          appendChecked(value, '\f');
          break;
        case 'n':
          appendChecked(value, '\n');
          break;
        case 'r':
          appendChecked(value, '\r');
          break;
        case 't':
          appendChecked(value, '\t');
          break;
        case 'u':
          appendChecked(value, readUnicodeEscape());
          break;
        default:
          throw invalid("escape-value");
      }
    }
    throw invalid("string-end");
  }

  private char readUnicodeEscape() {
    if (offset + 4 > input.length()) {
      throw invalid("unicode-end");
    }
    int value = 0;
    for (int index = 0; index < 4; ++index) {
      final int digit = Character.digit(input.charAt(offset++), 16);
      if (digit < 0) {
        throw invalid("unicode-value");
      }
      value = (value << 4) | digit;
    }
    return (char) value;
  }

  private Object readNumber() {
    final int start = offset;
    consume('-');
    if (consume('0')) {
      if (offset < input.length() && Character.isDigit(input.charAt(offset))) {
        throw invalid("number-leading-zero");
      }
    } else {
      readDigits();
    }
    boolean integer = true;
    if (consume('.')) {
      integer = false;
      readDigits();
    }
    if (offset < input.length() &&
        (input.charAt(offset) == 'e' || input.charAt(offset) == 'E')) {
      integer = false;
      offset++;
      if (!consume('+')) {
        consume('-');
      }
      readDigits();
    }
    if (start == offset) {
      throw invalid("value-token");
    }
    final String token = input.substring(start, offset);
    try {
      return integer ? Long.valueOf(token) : new BigDecimal(token);
    } catch (NumberFormatException exception) {
      throw invalid("number-range");
    }
  }

  private void readDigits() {
    final int start = offset;
    while (offset < input.length() && Character.isDigit(input.charAt(offset))) {
      offset++;
    }
    if (start == offset) {
      throw invalid("number-digits");
    }
  }

  private void readLiteral(String literal) {
    if (!input.regionMatches(offset, literal, 0, literal.length())) {
      throw invalid("literal-value");
    }
    offset += literal.length();
  }

  private void appendChecked(StringBuilder value, char character) {
    value.append(character);
    if (value.length() > MAX_STRING_CHARS) {
      throw invalid("string-size");
    }
  }

  private void skipWhitespace() {
    while (offset < input.length()) {
      final char character = input.charAt(offset);
      if (character != ' ' && character != '\n' && character != '\r' &&
          character != '\t') {
        return;
      }
      offset++;
    }
  }

  private boolean consume(char expected) {
    if (offset < input.length() && input.charAt(offset) == expected) {
      offset++;
      return true;
    }
    return false;
  }

  private void require(char expected) {
    if (!consume(expected)) {
      throw invalid("expected-token");
    }
  }

  private static IllegalArgumentException invalid(String code) {
    return new IllegalArgumentException("invalid-json:" + code);
  }
}
