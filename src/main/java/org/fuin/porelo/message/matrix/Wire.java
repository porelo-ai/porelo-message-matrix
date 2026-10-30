package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.net.http.HttpRequest;

/** One request and its HTTP status and bytes, independent of how the homeserver is reached. */
interface Wire {

    record Reply(int statusCode, byte[] body) {}

    Reply send(HttpRequest request) throws IOException, InterruptedException;
}
