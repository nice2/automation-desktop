import pandas as pd
import os
import sys

#Change working path to this file's path
abspath = os.path.abspath(__file__)
dname = os.path.dirname(abspath)
os.chdir(dname)

if len(sys.argv) > 1:
    # Path passed in (e.g. from the bot window)
    file_path = os.path.abspath(os.path.join(os.path.dirname(dname), sys.argv[1])) if not os.path.isabs(sys.argv[1]) else sys.argv[1]
else:
    from tkinter import Tk
    from tkinter.filedialog import askopenfilename
    Tk().withdraw() # we don't want a full GUI, so keep the root window from appearing
    file_path = askopenfilename(filetypes=[("Worksheet Files", "*.xlsx *.csv")]) # show an "Open" dialog box and return the path to the selected file

if not file_path:
    print("No file selected.")
    sys.exit(1)

print(f" * Reading {file_path}")

raw_df = ""

if file_path.endswith('.xlsx'):
    raw_df = pd.read_excel(file_path, engine='openpyxl')
elif file_path.endswith('.csv'):
    raw_df = pd.read_csv(file_path, encoding='utf-8')
    if not file_path.endswith('.csv'):
        raise ValueError("The selected file is not a .CSV Or .XLSX file.")


df = raw_df.sort_values(by = 'Zone', ascending=True)
df = df.round({"Zone": 1})
df = df[df['Zone'].notna()] #remove empty rows

zoneColumn = df['Zone']
typeColumn = df['Type']
locationColumn = df['Location']

print(" * Dataframe loaded and sorted by Zone. Starting checks...")


def check_decimal(number):
    number_str = str(number)
    if "." not in number_str:
        return False
    if number_str.endswith(".1") or number_str.endswith(".2"):
        return True
    else:
        return False


def check_column_headers(df):
    print(" * Checking if all headers are present...")
    required_columns = ['Zone', 'Type', 'Location']
    for column in required_columns:
        if column not in df.columns:
            errorMessage = f"Missing required column: {column}"
            raise ValueError(errorMessage)

"""
#If the first zone has a decimal, check if all zones are decimals (0.1 or 0.2)
def check_zone_format(zoneColumn):
    print(" * Checking if the zone column is in the correct format...")
    if(check_decimal(zoneColumn[0])):          
        for index, value in enumerate(zoneColumn):
            if(check_decimal(value) == False):
                errorMessage = f"Zone: {value}, row {index+1} is not in correct format, make sure it ends with .1 or .2"
                raise ValueError(errorMessage)
"""

# check if every .2 zone has a .1 zone
def check_subaddresses(zoneColumn):
    print(" * Checking if every .2 zone has a .1 zone...")
    for index, value in enumerate(zoneColumn):
        try:
            value = float(value)  # Ensure the value is numeric
            if check_decimal(value) and str(value).endswith(".2"):
                prev_zone = round(value - 0.1, 2)
                
                if prev_zone not in zoneColumn.astype(float).values:
                    errorMessage = f"Zone: {value}, row {index+1} is .2 zone but there is no zone number {prev_zone} in the list, make sure there is a .1 zone before it"
                    raise ValueError(errorMessage)
        except ValueError as e:
            raise ValueError(f"{e}")

    
# check if the columns are present in the dataframe
check_column_headers(df)
#check_zone_format(zoneColumn)

# check if every .2 zone has a .1 zone
check_subaddresses(zoneColumn)

df.to_csv('../assets/temp_zones.csv', index=False)
print(f" * Saved {len(df)} rows to assets/temp_zones.csv")
