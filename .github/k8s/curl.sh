#!/bin/bash
POD_NAME=$1
MAX_TIME=$2
STUDY_NAME=$3
URL=$4

kubectl exec -it $POD_NAME -- bash -c "curl --max-time $MAX_TIME -s -w '\n\n%{http_code}' -X POST '$URL' -H 'accept: application/json' -H 'Content-Type: application/json' -d '{\"studyIds\": [ \"$STUDY_NAME\" ]}'" > curl.out
RESPONSE_CODE=`tail -1 curl.out`
RESPONSE_BODY=`head -1 curl.out`
if [ "$RESPONSE_CODE" != "$OK_RESPONSE_CODE" ]; then
    echo "Request failed with response code '$RESPONSE_CODE', expected '$OK_RESPONSE_CODE'"
    exit 1
else
    if [ "$RESPONSE_BODY" == "$EMPTY_LIST_RESPONSE" ] || [ -z "$RESPONSE_BODY" ]; then 
        echo "Request failed with empty response"
        exit 1
    else
        if [[ "$RESPONSE_BODY" != *"$SEARCH_STR"* ]]; then
            echo "Response did not contain search string '$SEARCH_STR'"
            exit 1
        fi
    fi
    exit 0
fi
