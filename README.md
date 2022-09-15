# OpenNMS IMAP Alarms

This tool queries an OpenNMS instance for alarms and allows to retrieve these via IMAP. 
When accessed from an eMail-client a mail read will be acknowledged, a eMail marked as unread will be unacknowledged and a deleted eMail will be cleared.
It is recommended to configure the eMail-Client for this account to not use a trash folder and delete eMails immediately.

## Building

The project has some dependencies to OpenNMS artifacts and can be build with maven:

$ mvn install

## Running

After building the tool can be invoked by the shell script:

    $ imap-alarms.sh
    Usage: imap-alarms.sh [options...]

    --delay N               : Update delay in seconds. (default: 15)
    --imap-email VAL        : E-Mail address for accessing OpenNMS IMAP alarms.
                              (default: imap-alarms@opennms.org)
    --imap-password VAL     : Password for accessing OpenNMS IMAP alarms.
                              (default: secret)
    --imap-port N           : Port for IMAP server. (default: 1993)
    --imap-username VAL     : Username for accessing OpenNMS IMAP alarms.
                              (default: username)
    --keystore VAL          : Keystore file to use.
    --keystore-password VAL : Password for keystore file.
    --password VAL          : Password for accessing OpenNMS ReST endpoints.
    --url VAL               : URL of your OpenNMS installation. (default:
                              http://localhost:8980/opennms)
    --username VAL          : Username for accessing OpenNMS ReST endpoints.
    --verbose               : Verbose output. (default: false)

In order to connect to an OpenNMS instance you need to provide the URL and the credentials to query the ReST endpoints.

    $ imap-alarms.sh --url https://opennms.somewhere.org/opennms --username johndoe --password secret

You can also define common used command line parameters in a `imap-alarms.properties` file, for instance the URL and credentials:

    url=https://opennms.somewhere.org/opennms
    username=johndoe
    password=secret
    verbose=true

In order to replace the provided default self-signed certificate, you can pass in your own keystore:

    $ imap-alarms.sh --url https://opennms.somewhere.org/opennms --username johndoe --password secret --keystore="mykeystore.jks" --keystore-password="mypassword"
