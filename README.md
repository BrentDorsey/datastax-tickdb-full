Stock Ticker Price Time Series Data
===========================================

This is an example of using Cassandra as a stock ticker price data store for financial market data.  This example demonstrates 2 methods for storing time series data (binary blobs vs clustering columns) and demonstrates using blobs to improve performance by retrieving the entire time series data from a single row with very little scanning.

## Queries

The queries that we want to be able to run are

 - Get all the tick data for a symbol in an exchange (in a time range)
    - select * from tick_data where symbol ='NASDAQ-NFLX-2014-01-31';
    - select * from tick_data where symbol ='NASDAQ-NFLX-2014-01-31' and date > '2014-01-01 14:45:00' and date < '2014-01-01 15:00:00';

## Data

The data is generated from a stock ticker generator which uses a csv file to create random values from AMEX, NYSE and NASDAQ.

## Running the demo

You will need the java 8 runtime along with maven 3 and a local instance of Cassandra 3.x to run this demo.

This demo uses quite a lot of memory so it is worth setting the MAVEN_OPTS to run maven with more memory

    export MAVEN_OPTS=-Xmx512M

### DataStax Cassandra 3.x LocalHost Setup

 - [Download DataStax Cassandra 3.x](https://academy.datastax.com/planet-cassandra/cassandra)
 - The example code below assumes you have a bin directory in your Mac home directory ~/ where you store manually installed applications
 - Unpack the tarball to ~/bin/cassandras (plural)
 - Create a symbolic link to enable easily upgrading and changing versions : ln -s {/path/to/file-name} {link-name}
    - `ln -s ~/bin/cassandras/datastax-ddc-3.9.0 cassandra`
 - Create CASSANDRA_HOME environment variable
    - `vi ~/.bash_profile` (open your bash profile in vi)
    - `i` (enable insert mode)
    - Create CASSANDRA_HOME environment variable and it to your path
```
# ----- cassandra home
export CASSANDRA_HOME="/Users/{your username here}/bin/cassandra"
export PATH=$CASSANDRA_HOME/bin:$PATH
```
 - `esc:x` (press the escape key and type :x and hit enter to save changes)
 - `source ~/.bash_profile` (reload .bash_profile to load the new environment variable)
 - Start Cassandra
    - `./bin/cassandra/bin/cassandra`
 - Verify Cassandra is running and you can login
    - Cassandra is shipped with a default superuser the user name and password `cassandra`
    - `cqlsh localhost -u cassandra` (start [cqlsh](http://docs.datastax.com/en/cql/3.3/cql/cql_reference/cqlsh.html?hl=cqlsh) the CQL interactive terminal as the cassandra user)
    - `cassandra` (enter password)
    - After successfully logging in your bash prompt should be `cassandra@cqlsh>`
    - `exit` 
    
See [Installing DataStax Distribution of Apache Cassandra 3.x on any Linux-based platform and Mac OS X](http://docs.datastax.com/en/cassandra/3.x/cassandra/install/installTarball.html) for more detailed information.

### Clone and Build this repo

```
git clone https://github.com/BrentDorsey/datastax-tickdb-full.git
cd datastax-tickdb-full
```

#### Build datastax-timeseries-lib
```
cd datastax-timeseries-lib
mvn clean install
```

#### Package datastax-tickdb
```
cd ../datastax-tickdb
mvn package -DskipTests
```

#### Compile datastax-tickdata-comparison
```
cd ../datastax-tickdata-comparison
mvn clean compile
```
 
### Schema Setup

The commands in this section only need to be executed one time.  Executing the commands a second time will truncate the tables used in the demo and require you to repopulate time series data for the demo.

To create the keyspaces and tables for this demo in your local instance of Cassandra change to the datastax-tickdata-comparison directory and execute 
```
mvn clean compile exec:java -Dexec.mainClass="com.datastax.demo.SchemaSetup"
``` 

#### Binary Demo
```
CREATE TABLE if not exists datastax_tickdata_binary_demo.tick_data ( 
	symbol text,
	dates blob,
	ticks blob,
	PRIMARY KEY (symbol)
); 
```

#### Clustering Demo
```
CREATE TABLE if not exists datastax_tickdata_demo.tick_data ( 
	symbol text,
	date timestamp,
	value double,
	PRIMARY KEY (symbol, date)
) WITH CLUSTERING ORDER BY (date DESC);
```

The cql schema scripts are located in datastax-tickdata-comparison/src/main/resources/cql/

### Stock Ticker Price Time Series Data

The commands in this section create 4 threads, 2 for the Clustering demo and 2 for the Binary demo.  The first thread for each demo populates a queue with TimeSeries objects and the second thread reads the TimeSeries objects from the queue.

Each TimeSeries object contains a stock ticker symbol along with a dates array and a values array.  Each TimeSeries object contains thousands of time series data points recording a single stock's prices throughout a day.  The individual time series data points are stored in arrays.  The dates array stores the timestamp at each point when the stock price was recorded and the values array stores the value of the stock at that point in time.

```
public class TimeSeries {

	private String symbol;
	private long[] dates;
	private double[] values;
	
	public TimeSeries(String symbol, long[] dates, double[] values) {
		super();
		this.symbol = symbol;
		this.dates = dates;
		this.values = values;
	}
```

Both demos perform the following actions
 - Poll the queue checking for data to process
 - Read TimeSeries objects from the queue 

#### Clustering Demo (stores data as columns)

The business logic for this demo performs the following actions
 - Reads a TimeSeries object from the queue
 - Loops through the arrays extracting the individual time series data points
 - Executes multiple prepared cql insert statements, one for each time series data point
 - Persist **each time series data point as a new column** in the datastax_tickdata_demo.tick_data table
 - Note: This is the Cassandra equivalent to inserting a new row in relational database table 
 
#### Binary Demo (stores data as blobs)

The business logic for this demo performs the following actions
 - Reads a TimeSeries object from the queue 
 - Loads the contents of each array into a ByteBuffer
 - Executes a single prepared cql insert statement 
 - Persist **the entire time series data set as a blob stored a single cell** in the datastax_tickdata_binary_demo.tick_data table

#### Populate Times Series Data
 
By default the following command populate 2 days of data in the instance of Cassandra running on your localhost.
```
mvn clean compile exec:java -Dexec.mainClass="com.datastax.tickdata.Main"
```

The `-DcontactPoints` parameter can be used to customize the instance of Cassandra used for the demo by supplying one or more ip's or host names (no spaces) e.g. '-DcontactPoints=192.168.25.100,192.168.25.101'
```
mvn clean compile exec:java -Dexec.mainClass="com.datastax.tickdata.Main" -DcontactPoints=localhost
```
    
The `-DnoOfDays` parameter can be used to customize the number of days of historical for the demo.
```    
mvn clean compile exec:java -Dexec.mainClass="com.datastax.tickdata.Main" -DcontactPoints=localhost -DnoOfDays=30
```

To read a stock ticker time series data set
```
mvn clean compile exec:java -Dexec.mainClass="com.datastax.tickdata.Read" (-Dsymbol=NASDAQ-AAPL-2015-09-16)
```

Start the server by running
```
  ./run_server.sh
```

### Querying Time Series Data

Today's time series data

    http://localhost:7001/datastax-tickdb/rest/tickdb/get/NASDAQ/AAPL

Time series data between two dates

    http://localhost:7001/datastax-tickdb/rest/tickdb/get/bydatetime/NASDAQ/AAPL/20150914000000/20150917000000

Time series data between two dates broken into minute chunks

    http://localhost:7001/datastax-tickdb/rest/tickdb/get/bydatetime/NASDAQ/AAPL/20150914000000/20150917000000/MINUTE

Time series data between two dates broken into minute chunks and shown as candlesticks

    http://localhost:7001/datastax-tickdb/rest/tickdb/get/candlesticks/NASDAQ/AAPL/20150914000000/20150917000000/MINUTE_5

### Convert Clustered Time Series Data to Binary Time Series Data 

For all exchanges and symbols, run daily conversion of stock ticker data to binary data for long term storage and retrieval

    http://localhost:7001/datastax-tickdb/rest/tickdb/get/rundailyconversion

For a specific symbol and todays date, run daily conversion of stock ticker data to binary data for long term storage and retrieval

    http://localhost:7001/datastax-tickdb/rest/tickdb/get/rundailyconversionbysymbol/NASDAQ/AAPL

For a specific symbol and date, run daily conversion of stock ticker data to binary data for long term storage and retrieval

    http://localhost:7001/datastax-tickdb/rest/tickdb/get/rundailyconversionbysymbolanddate/NASDAQ/AAPL/20150917000000

### Notes

Dates are in format - yyyyMMddHHmmss

Periodicity's are
MINUTE
MINUTE_5
MINUTE_15
MINUTE_30
HOUR

To remove the tables and the schema, run the following.

    mvn clean compile exec:java -Dexec.mainClass="com.datastax.demo.SchemaTeardown"
