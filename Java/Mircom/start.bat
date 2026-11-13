javac -cp  ./dependencies/* Automation.java BotWindow.java ConfigBot.java FX400.java ZoneList.java Zone.java ./DataEntryBot.java FX2000.java Flexnet.java FX4000.java -d .
java --enable-native-access=ALL-UNNAMED -cp "./dependencies/*;." Automation
PAUSE
