1. For names like John, John Smith, Ethan Thomas, etc., I recommended not relying only on sentence-specific regexes. Instead, use a local/in-process NER or approved enterprise PII detector to identify PERSON entities
2. Deployment to cloud server
3. github actions for CI/CD
4. RAG and Vector DB integration
5. Prompt changes - llm should be derive any complex query having cotains, like, startswith, etc.. it should be able to dervie