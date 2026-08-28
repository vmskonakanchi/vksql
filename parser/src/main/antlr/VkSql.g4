grammar VkSql;

// ============ PARSER RULES ============

// Entry point
query
    : selectStatement EOF
    ;

selectStatement
    : SELECT selectList
      FROM tableRef
      (WHERE whereExpr=expr)?
      (GROUP BY groupByList)?
      (ORDER BY orderByList)?
      (LIMIT limitValue=INTEGER)?
    ;

// SELECT col1, col2, sum(col3)
selectList
    : selectItem (',' selectItem)*
    ;

selectItem
    : expr (AS? alias=IDENTIFIER)?     # selectExpr
    | STAR                              # selectAll
    ;

// FROM table or FROM table1 JOIN table2 ON ...
tableRef
    : tableName=IDENTIFIER                                          # simpleTable
    | left=tableRef JOIN right=tableRef ON onExpr=expr              # joinTable
    ;

// GROUP BY col1, col2
groupByList
    : expr (',' expr)*
    ;

// ORDER BY col1 ASC, col2 DESC
orderByList
    : orderByItem (',' orderByItem)*
    ;

orderByItem
    : expr (ASC | DESC)?
    ;

// ============ EXPRESSIONS ============

expr
    : left=expr op=(STAR | SLASH) right=expr                        # mulDiv
    | left=expr op=(PLUS | MINUS) right=expr                        # addSub
    | left=expr op=(GT | LT | GTE | LTE | EQ | NEQ) right=expr     # comparison
    | left=expr AND right=expr                                      # andExpr
    | left=expr OR right=expr                                       # orExpr
    | NOT expr                                                      # notExpr
    | functionName=IDENTIFIER '(' (expr (',' expr)*)? ')'           # functionCall
    | IDENTIFIER                                                    # columnRef
    | INTEGER                                                       # intLiteral
    | DECIMAL                                                       # decimalLiteral
    | STRING_LITERAL                                                # stringLiteral
    | '(' expr ')'                                                  # parenExpr
    ;

// ============ LEXER RULES ============

// Keywords (case-insensitive)
SELECT  : [sS][eE][lL][eE][cC][tT] ;
FROM    : [fF][rR][oO][mM] ;
WHERE   : [wW][hH][eE][rR][eE] ;
GROUP   : [gG][rR][oO][uU][pP] ;
BY      : [bB][yY] ;
ORDER   : [oO][rR][dD][eE][rR] ;
LIMIT   : [lL][iI][mM][iI][tT] ;
JOIN    : [jJ][oO][iI][nN] ;
ON      : [oO][nN] ;
AND     : [aA][nN][dD] ;
OR      : [oO][rR] ;
NOT     : [nN][oO][tT] ;
AS      : [aA][sS] ;
ASC     : [aA][sS][cC] ;
DESC    : [dD][eE][sS][cC] ;

// Operators
STAR    : '*' ;
SLASH   : '/' ;
PLUS    : '+' ;
MINUS   : '-' ;
GT      : '>' ;
LT      : '<' ;
GTE     : '>=' ;
LTE     : '<=' ;
EQ      : '=' ;
NEQ     : '!=' | '<>' ;

// Literals
INTEGER         : [0-9]+ ;
DECIMAL         : [0-9]+ '.' [0-9]+ ;
STRING_LITERAL  : '\'' (~'\'')* '\'' ;

// Identifiers
IDENTIFIER  : [a-zA-Z_][a-zA-Z_0-9]* ;

// Skip whitespace
WS  : [ \t\r\n]+ -> skip ;
