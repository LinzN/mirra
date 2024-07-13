package de.linzn.mirra.identitySystem;

public class UserToken {
    private final int id;
    private final String name;
    private final TokenSource source;

    UserToken(int id, String name, TokenSource source) {
        this.id = id;
        this.name = name;
        this.source = source;
    }

    public String getName() {
        return name;
    }

    public TokenSource getSource() {
        return source;
    }

    public int getId() {
        return id;
    }
}
