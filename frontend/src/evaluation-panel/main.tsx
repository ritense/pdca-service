import React from 'react';
import { createRoot } from 'react-dom/client';
import '@carbon/styles/css/styles.css';
import '../shared/styles.css';
import { EvaluationPanel } from './EvaluationPanel';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <EvaluationPanel />
  </React.StrictMode>
);
