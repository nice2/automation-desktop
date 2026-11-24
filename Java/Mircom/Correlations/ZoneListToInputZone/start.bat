python ../../tools/excelReader.py
javac -cp  ../../dependencies/* ../../DataEntryBot.java  ../../Zone.java ../../ZoneList.java  ZoneListToInputZone.java Automation.java -d .
java --enable-native-access=ALL-UNNAMED -cp "../../dependencies/*;."  Automation.java
PAUSE