package dev.mert.tvassistant;

final class Calculator {
  private final String text;
  private int pos;

  private Calculator(String s) {
    text = s;
  }

  static double evaluate(String s) {
    if (s == null || s.length() > 200) throw new IllegalArgumentException("Expression is too long");
    Calculator p = new Calculator(s);
    double v = p.sum();
    p.space();
    if (p.pos != s.length() || Double.isNaN(v) || Double.isInfinite(v))
      throw new IllegalArgumentException("Invalid arithmetic expression");
    return v;
  }

  private void space() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
  }

  private boolean take(char c) {
    space();
    if (pos < text.length() && text.charAt(pos) == c) {
      pos++;
      return true;
    }
    return false;
  }

  private double sum() {
    double v = product();
    while (true) {
      if (take('+')) v += product();
      else if (take('-')) v -= product();
      else return v;
    }
  }

  private double product() {
    double v = atom();
    while (true) {
      if (take('*')) v *= atom();
      else if (take('/')) {
        double d = atom();
        if (d == 0) throw new IllegalArgumentException("Division by zero");
        v /= d;
      } else if (take('%')) v %= atom();
      else return v;
    }
  }

  private double atom() {
    space();
    if (take('+')) return atom();
    if (take('-')) return -atom();
    if (take('(')) {
      double v = sum();
      if (!take(')')) throw new IllegalArgumentException("Missing parenthesis");
      return v;
    }
    int start = pos;
    while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.'))
      pos++;
    if (start == pos) throw new IllegalArgumentException("Expected a number");
    return Double.parseDouble(text.substring(start, pos));
  }
}
