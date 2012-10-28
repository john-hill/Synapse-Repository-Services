CREATE TABLE JDOCOMPETITION (
    ID bigint(20) NOT NULL,
    ETAG char(36) NOT NULL,
    NAME varchar(256)CHARACTER SET latin1 COLLATE latin1_bin NOT NULL,
    DESCRIPTION mediumblob DEFAULT NULL,
    OWNER_ID bigint(20) NOT NULL,
    CREATED_ON datetime NOT NULL,
    CONTENT_SOURCE varchar(256)CHARACTER SET latin1 COLLATE latin1_bin NOT NULL,
    STATUS int NOT NULL,
    PRIMARY KEY (ID),
    UNIQUE KEY JDOCOMPETITION_U1 (NAME),
    FOREIGN KEY (OWNER_ID) REFERENCES JDOUSERGROUP (ID)
);