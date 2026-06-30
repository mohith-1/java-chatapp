# Java Chat App

A lightweight desktop messenger built entirely in pure Java, showcasing what the language can do without leaning on a single external framework, runtime, or third-party library. Real-time messaging, live typing indicators, voice input, text-to-speech, custom backgrounds, and an honesty indicator, all built on Java's own standard library.

## Why This Project Highlights Java

Most chat app tutorials reach for Node.js with Socket.io, Python with Flask-SocketIO, or a JavaScript framework the moment they need real-time behavior or a GUI. This project deliberately does neither, and stays entirely inside the JDK to prove what's possible:

- Real-time bidirectional networking, done with raw java.net.Socket and ServerSocket, no WebSocket library
- A native desktop GUI, built with javax.swing, no Electron, no browser, no HTML/CSS/JS runtime
- Concurrent client handling, done with plain Thread and Runnable, no async framework
- Audio recording and playback, done with javax.sound.sampled, no native audio library
- HTTP API calls and JSON parsing, done with java.net.HttpURLConnection and manual string parsing, no Gson, no Jackson, no OkHttp
- Image loading and rendering, done with javax.imageio and Graphics2D, no external graphics library

Every feature below runs from two .java files. No build tool, no dependency manager, no pom.xml, no package.json. Clone it, open it in any IDE, hit run.

## Features

### Real-Time Messaging
Multiple users connect to one central server and chat instantly. Messages appear as soon as they're sent, no polling, no refresh.

### Live Typing Indicator
The moment someone starts typing, the other person sees "Name is typing" appear immediately, just like modern messaging apps. It clears automatically a moment after they stop, or instantly when the message is sent.

### Clean Desktop GUI
A proper native window with chat bubbles, colored avatars, timestamps, and a name-entry screen on launch, not a console window.

### Voice Input
Click the microphone button, speak, click again, and your spoken words are transcribed straight into the message box using OpenAI's Whisper API. Speak it, review it, send it.

### Text-to-Speech
Toggle a button and incoming messages get read aloud automatically using the operating system's built-in voice engine, so you can follow a conversation without watching the screen.

### Custom Backgrounds
Pick any image from your own device and it becomes the chat background across the whole window, scaled and cropped cleanly, with the header and input bar staying readable on top.

### FAB Indicator (Flip-Flop Alert Badge)
A small colored dot that gives the receiver insight into how a message was composed. If the sender typed something, cleared it completely, and typed something different before sending, the badge shows red for that message. If they sent it as originally typed, it stays green. The badge resets fresh for every new message.

## Project Structure

java-chat-app/
- ChatServer.java, the relay server, run once
- ChatClient.java, the GUI client, run once per user
- openai_key.txt, your OpenAI API key, for voice input only
- README.md

## Requirements

- JDK 17 or newer
- Any IDE, IntelliJ IDEA recommended, or a terminal with javac and java available
- A microphone, for voice input
- An OpenAI API key, for voice input only, everything else works without one

## How to Run

### 1. Start the Server

javac ChatServer.java
java ChatServer

Wait for the console to confirm it's listening on port 5000.

### 2. Set Up Voice Input, Optional

Get a free API key at platform.openai.com/api-keys, create a file named openai_key.txt in the project folder, and paste the key inside. Skip this step entirely if you don't want voice input, the rest of the app works fine without it.

### 3. Start a Client

javac ChatClient.java
java ChatClient

Enter a display name when prompted. Run it again to simulate a second user.

### 4. Chat

Type and send messages normally, watch the typing indicator update live, click the microphone to speak a message instead of typing it, toggle read-aloud to hear incoming messages, and pick a background image to personalize the window.

## Running Over a Local Network

By default, clients connect to localhost. To chat with someone on the same Wi-Fi, find the server machine's local IP address, then in ChatClient.java change the HOST value from localhost to the server's IP address, recompile, and run on the other machine.

## Protocol

Communication is plain text, one event per line, sent over a TCP socket.

- JOIN:name announces a client's username
- MSG:name:flag:text is a chat message, where flag is FAB:GREEN or FAB:RED
- TYPING:name means a user started typing
- STOP_TYPING:name means a user stopped typing
- LEFT:name means a client disconnected

The server broadcasts each event to all other connected clients without needing to understand or modify the contents, it simply relays whatever it receives.
##Output
https://github.com/mohith-1/java-chatapp/issues/1#issue-4779248350

## Technical Notes

The typing indicator fires on the first keystroke after being idle and clears automatically about 1.2 seconds after the last keystroke, or immediately when a message is sent, keeping network traffic minimal while still feeling instant.

The FAB indicator tracks the longest version of a message reached before the input box was cleared, then compares it against whatever gets typed and sent afterward, so it correctly detects a full clear even when done one backspace at a time rather than all at once.

Voice input records locally in WAV format using the system's default microphone, uploads it to OpenAI's transcription endpoint over HTTPS, and parses the returned text without any JSON library, just careful string handling.

Text-to-speech on Windows uses the built-in System.Speech engine via a background process call, requiring no additional installation.

## License

Free to use, modify, and learn from.
