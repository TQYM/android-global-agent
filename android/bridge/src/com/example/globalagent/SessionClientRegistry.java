package com.example.globalagent;

final class SessionClientRegistry {
  private static volatile AgentSessionClient client;

  private SessionClientRegistry() {}

  static void install(AgentSessionClient value) {
    client = value;
  }

  static AgentSessionClient get() {
    return client;
  }

  static void clear(AgentSessionClient value) {
    if (client == value) {
      client = null;
    }
  }
}
