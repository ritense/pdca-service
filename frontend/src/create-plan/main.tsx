import React from 'react';
import { createRoot } from 'react-dom/client';
import '@carbon/styles/css/styles.css';
import '../shared/styles.css';
import './create-plan.css';
import { CreatePlan } from './CreatePlan';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <CreatePlan />
  </React.StrictMode>
);
